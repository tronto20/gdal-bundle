package dev.gdal4k

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.provider.DocumentsContract
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dev.gdal4k.runtime.*
import java.io.File
import java.io.IOException
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.*

/** Test-only provider; never packaged in a published library. */
class UriFixtureProvider : ContentProvider() {
    @Volatile var lastAsset: TrackedAsset? = null
    var opened: CountDownLatch? = null
    var release: CountDownLatch? = null
    var namedFiles: Map<String, ByteArray> = emptyMap()
    val openedNames = mutableListOf<String>()
    val openedAssets = mutableListOf<TrackedAsset>()
    var denyListing = false

    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/tiff"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? {
        if (denyListing) throw SecurityException("Directory permission denied")
        if (namedFiles.isEmpty()) return null
        return MatrixCursor(requireNotNull(projection)).apply {
            for (name in namedFiles.keys) addRow(arrayOf("root:$name", name, "application/octet-stream"))
            addRow(arrayOf("root:folder", "folder", DocumentsContract.Document.MIME_TYPE_DIR))
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        check(mode == "r")
        val entry = requireNotNull(uri.lastPathSegment).removePrefix("root:")
        openedNames.add(entry)
        if (uri.lastPathSegment == "denied") throw SecurityException("URI read permission denied")
        val bytes = if (namedFiles.isNotEmpty()) namedFiles[entry] ?: throw FileNotFoundException(entry) else android.util.Base64.decode(
            "SUkqAAgAAAAOAAABBAABAAAAAwAAAAEBBAABAAAAAgAAAAIBAwABAAAACAAAAAMBAwABAAAAAQAAAAYBAwABAAAAAQAAABEBBAABAAAAHgEAABUBAwABAAAAAQAAABYBBAABAAAAAgAAABcBBAABAAAABgAAAFMBAwABAAAAAQAAAA6DDAADAAAAtgAAAIKEDAAGAAAAzgAAAK+HAwAQAAAA/gAAAIGkAgACAAAAMAAAAAAAAAAAAAAAAADgPwAAAAAAAOA/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABZQAAAAAAAAElAAAAAAAAAAAABAAEAAAADAAAEAAABAAIAAQQAAAEAAQAACAAAAQDmEAABAgME/w==",
            android.util.Base64.DEFAULT,
        )
        val asset = if (uri.lastPathSegment == "pipe") {
            val (read, write) = ParcelFileDescriptor.createPipe()
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(bytes) }
            TrackedAsset(read, 0, AssetFileDescriptor.UNKNOWN_LENGTH) {}
        } else {
            val file = File.createTempFile("gdal4k-uri-", ".bin", requireNotNull(context).cacheDir)
            val offset = if (uri.lastPathSegment == "offset") 128 else 0
            file.outputStream().use {
                it.write(ByteArray(offset) { 90 })
                it.write(if (uri.lastPathSegment == "invalid") byteArrayOf(1, 2, 3) else bytes)
                if (offset > 0) it.write(ByteArray(32) { 90 })
            }
            val length = when (uri.lastPathSegment) {
                "empty" -> 0L
                "bad-range" -> file.length() + 1
                "invalid" -> 3L
                else -> bytes.size.toLong()
            }
            TrackedAsset(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), offset.toLong(), length) { file.delete() }
        }
        lastAsset = asset
        openedAssets.add(asset)
        opened?.countDown()
        release?.let { check(it.await(20, TimeUnit.SECONDS)) }
        return asset
    }
}

class TrackedAsset(fd: ParcelFileDescriptor, offset: Long, length: Long, private val cleanup: () -> Unit) :
    AssetFileDescriptor(fd, offset, length) {
    @Volatile var closed = false
        private set
    override fun close() {
        closed = true
        try { super.close() } finally { cleanup() }
    }
}

class AndroidUriDatasetTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val uri = Uri.parse("content://dev.gdal4k.uri-fixture/image.tif")
    private fun provider(): Pair<UriFixtureProvider, ContentResolver> {
        val provider = UriFixtureProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "dev.gdal4k.uri-override" })
        return provider to ContentResolver.wrap(provider)
    }
    private fun input(name: String) = Uri.parse("content://dev.gdal4k.uri-override/$name")
    private suspend fun verify(dataset: Dataset) {
        assertEquals(3, dataset.getRasterXSize())
        assertEquals(2, dataset.getRasterYSize())
        assertContentEquals(doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0, 255.0), dataset.readRaster())
    }

    private suspend fun shapefile(runtime: GdalRuntime): Map<String, ByteArray> = runtime.openDataset("points.geojson",
        """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[10,45]},"properties":{"name":"road-one"}}]}""".encodeToByteArray()
    ).use { it.translateVector("ESRI Shapefile", "roads.shp", listOf("-t_srs", "EPSG:3857")) }.files.associate { it.name to it.bytes }

    private suspend fun verifyShape(dataset: Dataset) {
        val output = dataset.translateVector(arguments = listOf("-t_srs", "EPSG:4326"))
        val feature = Json.parseToJsonElement(output.mainFile().bytes.decodeToString()).jsonObject.getValue("features").jsonArray.single().jsonObject
        assertEquals("road-one", feature.getValue("properties").jsonObject.getValue("name").jsonPrimitive.content)
        val point = feature.getValue("geometry").jsonObject.getValue("coordinates").jsonArray
        assertEquals(10.0, point[0].jsonPrimitive.double, 1e-7)
        assertEquals(45.0, point[1].jsonPrimitive.double, 1e-7)
    }

    @Test fun shapefileUriSetReadsSidecarsLazilyAndClosesThem() = runTest(timeout = 120.seconds) {
        val (provider, resolver) = provider()
        try {
            val runtime = Gdal4k.runtime()
            // Exercise UTF-8 JNI names, including a surrogate pair.
            val main = "도로 🗺.shp"
            provider.namedFiles = shapefile(runtime).mapKeys { it.key.replace("roads", "도로 🗺") } + ("unused.bin" to byteArrayOf(99))
            val files = provider.namedFiles.keys.associateWith { input(it) }
            runtime.openDataset(main, files, resolver).use { dataset ->
                verifyShape(dataset)
                assertTrue(provider.openedNames.any { it.endsWith(".dbf") })
                assertTrue(provider.openedNames.any { it.endsWith(".shx") })
                assertTrue(provider.openedNames.any { it.endsWith(".prj") })
                assertFalse("unused.bin" in provider.openedNames)
            }
            assertTrue(provider.openedAssets.all { it.closed })
            runtime.openDataset(main, files, resolver).use { verifyShape(it) }
            assertTrue(provider.openedAssets.all { it.closed })
        } finally { Gdal4k.close() }
    }

    @Test fun shapefileGrantedTreeAndListingPermission() = runTest(timeout = 120.seconds) {
        val (provider, resolver) = provider()
        try {
            val runtime = Gdal4k.runtime()
            provider.namedFiles = shapefile(runtime) + ("unused.bin" to byteArrayOf(99))
            val tree = DocumentsContract.buildTreeDocumentUri("dev.gdal4k.uri-override", "root")
            runtime.openDataset("roads.shp", directory = tree, resolver = resolver).use { verifyShape(it) }
            assertFalse("unused.bin" in provider.openedNames)
            assertTrue(provider.openedAssets.all { it.closed })
            provider.denyListing = true
            assertFailsWith<SecurityException> { runtime.openDataset("roads.shp", directory = tree, resolver = resolver) }
        } finally { Gdal4k.close() }
    }

    @Test fun directoryFailureAndGlobalCloseReleaseDescriptors() = runTest(timeout = 120.seconds) {
        val (provider, resolver) = provider()
        try {
            val runtime = Gdal4k.runtime()
            provider.namedFiles = shapefile(runtime)
            val files = provider.namedFiles.keys.associateWith { input(it) }
            // The driver can regard DBF failure as optional; the bridge must preserve the permission error.
            assertFailsWith<SecurityException> {
                runtime.openDataset("roads.shp", files + ("roads.dbf" to input("denied")), resolver)
            }
            assertTrue(provider.openedAssets.all { it.closed })
            runtime.openDataset("roads.shp", files, resolver).also { verifyShape(it) }
            Gdal4k.close()
            assertTrue(provider.openedAssets.all { it.closed })
        } finally { Gdal4k.close() }
    }

    @Test fun cancellationDuringDirectoryOpenClosesSidecars() = runTest(timeout = 120.seconds) {
        val (provider, resolver) = provider()
        try {
            val runtime = Gdal4k.runtime()
            provider.namedFiles = shapefile(runtime)
            val files = provider.namedFiles.keys.associateWith { input(it) }
            provider.opened = CountDownLatch(1)
            provider.release = CountDownLatch(1)
            val task = launch(Dispatchers.Default) { runtime.openDataset("roads.shp", files, resolver) }
            withContext(Dispatchers.IO) {
                assertTrue(requireNotNull(provider.opened).await(20, TimeUnit.SECONDS))
                task.cancel()
                requireNotNull(provider.release).countDown()
            }
            task.join()
            assertTrue(provider.openedAssets.isNotEmpty())
            assertTrue(provider.openedAssets.all { it.closed })
        } finally { provider.release?.countDown(); Gdal4k.close() }
    }

    @Test fun automaticResolverAndReopen() = runTest(timeout = 120.seconds) {
        try {
            val runtime = Gdal4k.runtime()
            // No initializer or resolver argument: the merged Startup metadata must register it.
            repeat(2) { runtime.openDataset(uri).use { verify(it) } }
        } finally { Gdal4k.close() }
    }

    @Test fun uriStringsUseAutomaticResolver() = runTest(timeout = 120.seconds) {
        try {
            val runtime = Gdal4k.runtime()
            repeat(2) { runtime.openDataset(uri.toString()).use { verify(it) } }
            assertFailsWith<IOException> { runtime.openDataset(uri.buildUpon().path("/pipe").build().toString()) }
            assertFailsWith<SecurityException> { runtime.openDataset(uri.buildUpon().path("/denied").build().toString()) }
            assertFailsWith<IllegalArgumentException> {
                runtime.openDataset(uri.toString(), DatasetOpenOptions(accessMode = DatasetAccessMode.Update))
            }
        } finally { Gdal4k.close() }
    }

    @Test fun uriStringsAllowExplicitResolver() = runTest(timeout = 120.seconds) {
        val (provider, resolver) = provider()
        try {
            val runtime = Gdal4k.runtime()
            runtime.openDataset(input("offset").toString(), resolver = resolver).use { verify(it) }
            assertTrue(requireNotNull(provider.lastAsset).closed)
        } finally { Gdal4k.close() }
    }

    @Test fun nativePathsAndEncodedFileUriStrings() = runTest(timeout = 120.seconds) {
        val (provider, resolver) = provider()
        val fixture = provider.openAssetFile(input("image.tif"), "r")
        val directory = File(context.cacheDir, "gdal4k string 경로").apply { mkdirs() }
        val file = File(directory, "image with spaces.tif")
        try {
            fixture.createInputStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            val runtime = Gdal4k.runtime()
            runtime.openDataset(file.absolutePath).use { verify(it) }
            runtime.openDataset(file.absolutePath, resolver = resolver).use { verify(it) }
            runtime.openDataset(Uri.fromFile(file).toString()).use { verify(it) }
        } finally { fixture.close(); file.delete(); directory.delete(); Gdal4k.close() }
    }

    @Test fun explicitResolverAndOriginalDatasetHandle() = runTest(timeout = 120.seconds) {
        val (provider, resolver) = provider()
        try {
            val runtime = Gdal4k.runtime()
            runtime.openDataset(input("image.tif"), resolver = resolver).use {
                assertFalse(requireNotNull(provider.lastAsset).closed)
                verify(it)
                assertTrue(runtime.datasetInfo(it, DatasetInfoOptions(listOf("-json"))).contains("GTiff"))
            }
            assertTrue(requireNotNull(provider.lastAsset).closed)
        } finally { Gdal4k.close() }
    }

    @Test fun assetSubrangeIsRespected() = runTest(timeout = 120.seconds) {
        val (provider, resolver) = provider()
        try {
            Gdal4k.runtime().openDataset(input("offset"), resolver = resolver).use { verify(it) }
            assertTrue(requireNotNull(provider.lastAsset).closed)
        } finally { Gdal4k.close() }
    }

    @Test fun nonSeekableProviderIsRejectedAndClosed() = runTest(timeout = 120.seconds) {
        val (provider, resolver) = provider()
        try {
            val failure = assertFailsWith<IOException> { Gdal4k.runtime().openDataset(input("pipe"), resolver = resolver) }
            assertTrue(failure.message.orEmpty().contains("seek"))
            assertTrue(requireNotNull(provider.lastAsset).closed)
        } finally { Gdal4k.close() }
    }

    @Test fun openFailuresCloseTheDescriptor() = runTest(timeout = 120.seconds) {
        val (provider, resolver) = provider()
        try {
            val runtime = Gdal4k.runtime()
            assertFailsWith<GdalException> { runtime.openDataset(input("invalid"), resolver = resolver) }
            assertTrue(requireNotNull(provider.lastAsset).closed)
            for (name in listOf("bad-range", "empty")) {
                assertFailsWith<IOException> { runtime.openDataset(input(name), resolver = resolver) }
                assertTrue(requireNotNull(provider.lastAsset).closed)
            }
            runtime.openDataset(input("image.tif"), resolver = resolver).use { verify(it) }
        } finally { Gdal4k.close() }
    }

    @Test fun permissionsAndUpdateAreNotOverridden() = runTest(timeout = 120.seconds) {
        val (provider, resolver) = provider()
        try {
            val runtime = Gdal4k.runtime()
            assertFailsWith<SecurityException> { runtime.openDataset(input("denied"), resolver = resolver) }
            assertFailsWith<IllegalArgumentException> {
                runtime.openDataset(input("image.tif"), resolver = resolver,
                    options = DatasetOpenOptions(accessMode = DatasetAccessMode.Update))
            }
            assertNull(provider.lastAsset)
        } finally { Gdal4k.close() }
    }

    @Test fun globalCloseReleasesOutstandingInputs() = runTest(timeout = 120.seconds) {
        val (provider, resolver) = provider()
        try {
            Gdal4k.runtime().openDataset(input("image.tif"), resolver = resolver)
            assertFalse(requireNotNull(provider.lastAsset).closed)
            Gdal4k.close()
            assertTrue(requireNotNull(provider.lastAsset).closed)
        } finally { Gdal4k.close() }
    }

    @Test fun cancellationWhileOpeningClosesTheDescriptor() = runTest(timeout = 120.seconds) {
        val (provider, resolver) = provider()
        provider.opened = CountDownLatch(1)
        provider.release = CountDownLatch(1)
        try {
            val runtime = Gdal4k.runtime()
            val task = launch(Dispatchers.Default) { runtime.openDataset(input("image.tif"), resolver = resolver) }
            withContext(Dispatchers.IO) {
                assertTrue(requireNotNull(provider.opened).await(20, TimeUnit.SECONDS))
                task.cancel()
                requireNotNull(provider.release).countDown()
            }
            task.join()
            assertTrue(requireNotNull(provider.lastAsset).closed)
        } finally { provider.release?.countDown(); Gdal4k.close() }
    }
}
