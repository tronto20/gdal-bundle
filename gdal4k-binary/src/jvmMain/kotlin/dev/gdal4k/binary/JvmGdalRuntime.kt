package dev.gdal4k.binary

import dev.gdal4k.runtime.Band
import dev.gdal4k.runtime.CoordinateTransformation
import dev.gdal4k.runtime.GCP
import dev.gdal4k.runtime.Dataset
import dev.gdal4k.runtime.DatasetAccessMode
import dev.gdal4k.runtime.DatasetInfoOptions
import dev.gdal4k.runtime.DatasetKind
import dev.gdal4k.runtime.Feature
import dev.gdal4k.runtime.FeatureDefn
import dev.gdal4k.runtime.FieldDefn
import dev.gdal4k.runtime.GeomFieldDefn
import dev.gdal4k.runtime.Geometry
import dev.gdal4k.runtime.DatasetOpenOptions
import dev.gdal4k.runtime.Driver
import dev.gdal4k.runtime.GdalPlatform
import dev.gdal4k.runtime.GdalRuntime
import dev.gdal4k.runtime.Layer
import dev.gdal4k.runtime.SpatialReference
import org.gdal.gdal.Band as NativeBand
import org.gdal.gdal.GCP as NativeGCP
import org.gdal.gdal.Driver as NativeDriver
import org.gdal.gdal.Dataset as NativeDataset
import org.gdal.gdal.InfoOptions
import org.gdal.gdal.gdal
import org.gdal.gdalconst.gdalconstConstants
import org.gdal.ogr.Feature as NativeFeature
import org.gdal.ogr.FeatureDefn as NativeFeatureDefn
import org.gdal.ogr.FieldDefn as NativeFieldDefn
import org.gdal.ogr.GeomFieldDefn as NativeGeomFieldDefn
import org.gdal.ogr.Geometry as NativeGeometry
import org.gdal.ogr.Layer as NativeLayer
import org.gdal.osr.CoordinateTransformation as NativeCoordinateTransformation
import org.gdal.osr.SpatialReference as NativeSpatialReference
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.Locale
import java.util.Vector
import java.security.MessageDigest

private const val BUNDLED_GDAL_ARCHIVE_RESOURCE = "gdal/gdal-bundle.txz"
private const val GDAL_JNI_NATIVE_LIBRARY_PATH_PROPERTY = "gdal4k.gdaljni.path"

private fun stringVector(values: List<String>): Vector<String> {
    return Vector<String>().apply {
        values.forEach { add(it) }
    }
}

private fun nativeGcps(gcps: List<GCP>): Array<NativeGCP> {
    return gcps.map { gcp ->
        (gcp as? JvmGCP)?.delegate
            ?: throw IllegalArgumentException("GCP was not created by the JVM GDAL runtime.")
    }.toTypedArray()
}

actual object Gdal4kBinary {
    @Volatile
    private var runtime: GdalRuntime? = null

    actual suspend fun prepare(bundleDir: String?) {
        if (runtime != null) {
            return
        }

        synchronized(this) {
            if (runtime == null) {
                runtime = JvmGdalRuntime.initialize(bundleDir)
            }
        }
    }

    actual fun runtime(): GdalRuntime {
        return runtime ?: error("Gdal4kBinary.prepare() must be called before runtime().")
    }
}

private class JvmGdalRuntime private constructor() : GdalRuntime {
    override fun getDriver(name: String): Driver? {
        return gdal.GetDriverByName(name)?.let(::JvmDriver)
    }

    override fun createGCP(
        gcpX: Double,
        gcpY: Double,
        gcpZ: Double,
        gcpPixel: Double,
        gcpLine: Double,
        info: String,
        id: String,
    ): GCP {
        return JvmGCP(NativeGCP(gcpX, gcpY, gcpZ, gcpPixel, gcpLine, info, id))
    }

    override fun createSpatialReference(): SpatialReference {
        return JvmSpatialReference(NativeSpatialReference())
    }

    override fun createSpatialReference(definition: String): SpatialReference {
        val spatialReference = NativeSpatialReference()
        val result = spatialReference.SetFromUserInput(definition)
        if (result != 0) {
            spatialReference.delete()
            throw IllegalArgumentException(
                "Unable to parse spatial reference: $definition\n${gdal.GetLastErrorMsg()}",
            )
        }
        return JvmSpatialReference(spatialReference)
    }

    override fun createCoordinateTransformation(
        source: SpatialReference,
        target: SpatialReference,
    ): CoordinateTransformation? {
        val nativeSource = (source as? JvmSpatialReference)?.delegate
            ?: throw IllegalArgumentException("SpatialReference was not created by the JVM GDAL runtime.")
        val nativeTarget = (target as? JvmSpatialReference)?.delegate
            ?: throw IllegalArgumentException("SpatialReference was not created by the JVM GDAL runtime.")
        return NativeCoordinateTransformation.CreateCoordinateTransformation(nativeSource, nativeTarget)
            ?.let(::JvmCoordinateTransformation)
    }

    override fun openDataset(source: String, options: DatasetOpenOptions): Dataset {
        val nativeDataset = openNativeDataset(source, options)
        return JvmDataset(nativeDataset)
    }

    override fun datasetInfo(dataset: Dataset, options: DatasetInfoOptions): String {
        val nativeDataset = (dataset as? JvmDataset)?.delegate
            ?: throw IllegalArgumentException("Dataset was not created by the JVM GDAL runtime.")

        val infoOptions = InfoOptions(stringVector(options.arguments))
        return try {
            gdal.GDALInfo(nativeDataset, infoOptions)
                ?.trimEnd()
                ?.ifBlank { "gdalinfo returned empty output." }
                ?: "gdalinfo returned empty output."
        } finally {
            infoOptions.delete()
        }
    }

    companion object {
        fun initialize(bundleDir: String?): GdalRuntime {
            debug("initialize(bundleDir=${bundleDir ?: "<null>"})")
            val gdalDir = locateBundleDir(bundleDir)
            debug("bundle directory resolved to ${gdalDir.absolutePath}")
            loadBundle(gdalDir)
            debug("GDAL runtime initialized")
            return JvmGdalRuntime()
        }

        private fun openNativeDataset(source: String, options: DatasetOpenOptions): NativeDataset {
            val flags = openFlags(options)
            val openOptions = options.openOptions.takeIf { it.isNotEmpty() }?.let(::stringVector)
            val dataset = if (openOptions != null) {
                gdal.OpenEx(source, flags, null, openOptions)
            } else {
                gdal.OpenEx(source, flags)
            }

            return dataset
                ?: throw IllegalStateException("Unable to open dataset: $source\n${gdal.GetLastErrorMsg()}")
        }

        private fun openFlags(options: DatasetOpenOptions): Long {
            var flags = gdalconstConstants.OF_VERBOSE_ERROR.toLong()
            flags = flags or when (options.accessMode) {
                DatasetAccessMode.ReadOnly -> gdalconstConstants.OF_READONLY.toLong()
                DatasetAccessMode.Update -> gdalconstConstants.OF_UPDATE.toLong()
            }
            flags = flags or when (options.kind) {
                DatasetKind.Any -> 0L
                DatasetKind.Raster -> gdalconstConstants.OF_RASTER.toLong()
                DatasetKind.Vector -> gdalconstConstants.OF_VECTOR.toLong()
            }
            return flags
        }

        private fun loadBundle(bundleDir: File) {
            debug("loadBundle(${bundleDir.absolutePath})")
            require(bundleDir.exists() && bundleDir.isDirectory) {
                "GDAL bundle directory not found: ${bundleDir.absolutePath}"
            }

            val nativeLibraryName = System.mapLibraryName("gdalalljni")
            val nativeLibrary = File(bundleDir, "lib/$nativeLibraryName")
            require(nativeLibrary.exists()) {
                "JNI library not found: ${nativeLibrary.absolutePath}"
            }

            System.setProperty(GDAL_JNI_NATIVE_LIBRARY_PATH_PROPERTY, nativeLibrary.absolutePath)
            resignBundleForMacos(bundleDir)

            debug("native library path configured for gdalJNI")

            val dataDir = File(bundleDir, "share/gdal")
            val projDir = File(bundleDir, "share/proj")
            require(dataDir.exists()) {
                "GDAL data directory not found: ${dataDir.absolutePath}"
            }
            require(projDir.exists()) {
                "PROJ data directory not found: ${projDir.absolutePath}"
            }

            gdal.SetConfigOption("GDAL_DATA", dataDir.absolutePath)
            gdal.SetConfigOption("PROJ_DATA", projDir.absolutePath)

            val pluginsDir = File(bundleDir, "gdalplugins")
            if (pluginsDir.exists()) {
                gdal.SetConfigOption("GDAL_DRIVER_PATH", pluginsDir.absolutePath)
            }

            debug("gdal.AllRegister()")
            gdal.AllRegister()
            debug("gdal.AllRegister finished")
        }

        private fun locateBundleDir(bundleDir: String?): File {
            val platform = System.getProperty("gdal.bundle.platform")
                ?.takeIf { it.isNotBlank() }
                ?: GdalPlatform.current().classifier

            val candidates = linkedSetOf<File>()
            bundleDir?.takeIf { it.isNotBlank() }?.let { candidates += File(it) }

            val explicit = System.getProperty("gdal.bundle.dir") ?: System.getenv("GDAL_BUNDLE_DIR")
            if (!explicit.isNullOrBlank()) {
                candidates += File(explicit)
            }

            val resourcesDir = System.getProperty("compose.application.resources.dir")
            if (!resourcesDir.isNullOrBlank()) {
                candidates += File(resourcesDir)
            }

            for (candidate in candidates) {
                resolveBundleCandidate(candidate, platform)?.let { return it }
            }

            loadBundledArchive(platform)?.let { return it }

            val checked = candidates.joinToString("\n") { "- ${it.absolutePath}" }
            throw IllegalStateException(
                buildString {
                    append("GDAL bundle not found for platform ")
                    append(platform)
                    append(
                        ". Set gdal.bundle.dir or GDAL_BUNDLE_DIR, pass bundleDir to Gdal4kBinary.prepare(), " +
                            "or depend on dev.tronto.gdal4k:gdal4k-binary:<version>:$platform.",
                    )
                    if (checked.isNotBlank()) {
                        append("\nChecked roots:\n")
                        append(checked)
                    }
                },
            )
        }

        private fun loadBundledArchive(platform: String): File? {
            val classLoader = JvmGdalRuntime::class.java.classLoader
                ?: Thread.currentThread().contextClassLoader
                ?: return null

            val resourceStream = classLoader.getResourceAsStream(BUNDLED_GDAL_ARCHIVE_RESOURCE)
                ?: return null

            val tempArchive = Files.createTempFile("gdal4k-binary-$platform-", ".txz").toFile()
            tempArchive.deleteOnExit()

            resourceStream.use { input ->
                tempArchive.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            debug("loaded bundled GDAL archive resource at ${tempArchive.absolutePath}")
            return extractTxzArchive(tempArchive, platform)
        }

        private fun resignBundleForMacos(bundleDir: File) {
            if (!isMacos()) {
                return
            }

            val filesToSign = buildList {
                listOf(
                    File(bundleDir, "lib"),
                    File(bundleDir, "gdalplugins"),
                ).forEach { root ->
                    if (!root.exists()) {
                        return@forEach
                    }
                    root.walkTopDown()
                        .filter { candidate ->
                            candidate.isFile && (
                                candidate.name.endsWith(".dylib") ||
                                    candidate.name.endsWith(".so") ||
                                    candidate.name.endsWith(".jnilib")
                                )
                        }
                        .forEach { add(it) }
                }
            }

            if (filesToSign.isEmpty()) {
                return
            }

            debug("re-signing ${filesToSign.size} macOS binaries before System.load()")
            filesToSign.forEach { file ->
                val process = ProcessBuilder("codesign", "--force", "--sign", "-", file.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                require(exitCode == 0) {
                    buildString {
                        append("codesign failed for ")
                        append(file.absolutePath)
                        append(" (exit ")
                        append(exitCode)
                        append(")")
                        if (output.isNotBlank()) {
                            append('\n')
                            append(output.trim())
                        }
                    }
                }
            }
        }

        private fun isMacos(): Boolean {
            return System.getProperty("os.name")
                .lowercase(Locale.ROOT)
                .contains("mac")
        }

        private fun resolveBundleCandidate(candidate: File, platform: String): File? {
            if (!candidate.exists()) {
                return null
            }

            if (candidate.isFile) {
                if (isTxzArchive(candidate)) {
                    return extractTxzArchive(candidate, platform)
                }
                return null
            }

            findArchiveCandidate(candidate, platform)?.let { archive ->
                return extractTxzArchive(archive, platform)
            }

            return normalizeBundleDir(candidate, platform)
        }

        private fun findArchiveCandidate(directory: File, platform: String): File? {
            val candidates = listOf(
                File(directory, "gdal.txz"),
                File(directory, "$platform.txz"),
                File(File(directory, platform), "gdal.txz"),
                File(File(directory, "gdal"), "$platform.txz"),
            )

            return candidates.firstOrNull { it.isFile && isTxzArchive(it) }
        }

        private fun isTxzArchive(file: File): Boolean {
            val name = file.name.lowercase()
            return name.endsWith(".txz") || name.endsWith(".tar.xz")
        }

        private fun extractTxzArchive(archive: File, platform: String): File {
            debug("extractTxzArchive(${archive.absolutePath}, platform=$platform)")
            val cacheRoot = File(File(System.getProperty("java.io.tmpdir"), "gdal4k-cache"), platform)
            val cacheKey = sha256(archive)
            val extractRoot = File(cacheRoot, cacheKey)
            val marker = File(extractRoot, ".complete")

            if (marker.isFile) {
                debug("using cached extraction at ${extractRoot.absolutePath}")
                return requireNotNull(normalizeBundleDir(extractRoot, platform)) {
                    "Cached GDAL bundle extraction is incomplete: ${extractRoot.absolutePath}"
                }
            }

            debug("extracting archive to ${extractRoot.absolutePath}")
            extractRoot.deleteRecursively()
            extractRoot.mkdirs()
            unpackTxz(archive, extractRoot.toPath())
            marker.writeText("${archive.absolutePath}\n${archive.length()}\n${archive.lastModified()}")
            debug("archive extraction completed")

            return requireNotNull(normalizeBundleDir(extractRoot, platform)) {
                "Extracted GDAL bundle is missing expected files: ${extractRoot.absolutePath}"
            }
        }

        private fun unpackTxz(archive: File, destination: Path) {
            debug("unpackTxz(${archive.absolutePath}, ${destination.toAbsolutePath().normalize()})")
            val destinationRoot = destination.toAbsolutePath().normalize()
            FileInputStream(archive).use { fileInput ->
                BufferedInputStream(fileInput).use { buffered ->
                    XZCompressorInputStream(buffered).use { xzInput ->
                        TarArchiveInputStream(xzInput).use { tarInput ->
                            while (true) {
                                val entry = tarInput.nextTarEntry ?: break
                                val outputPath = destinationRoot.resolve(entry.name).normalize()
                                require(outputPath.startsWith(destinationRoot)) {
                                    "Blocked suspicious archive entry: ${entry.name}"
                                }
                                extractTarEntry(tarInput, entry, outputPath)
                            }
                        }
                    }
                }
            }
            debug("unpackTxz finished")
        }

        private fun extractTarEntry(tarInput: TarArchiveInputStream, entry: TarArchiveEntry, outputPath: Path) {
            when {
                entry.isDirectory -> {
                    Files.createDirectories(outputPath)
                    applyPermissions(outputPath, entry.mode)
                }

                entry.isSymbolicLink -> {
                    Files.createDirectories(outputPath.parent)
                    Files.deleteIfExists(outputPath)
                    Files.createSymbolicLink(outputPath, Paths.get(entry.linkName))
                }

                else -> {
                    Files.createDirectories(outputPath.parent)
                    Files.newOutputStream(outputPath).use { output ->
                        copyLimited(tarInput, output, entry.size)
                    }
                    applyPermissions(outputPath, entry.mode)
                }
            }
        }

        private fun copyLimited(input: TarArchiveInputStream, output: java.io.OutputStream, size: Long) {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = size
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read < 0) {
                    throw EOFException("Unexpected end of archive while extracting GDAL bundle.")
                }
                output.write(buffer, 0, read)
                remaining -= read.toLong()
            }
        }

        private fun applyPermissions(path: Path, mode: Int) {
            if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) == null) {
                return
            }
            val permissions = mutableSetOf<PosixFilePermission>()

            if (mode and 0b100_000_000 != 0) permissions += PosixFilePermission.OWNER_READ
            if (mode and 0b010_000_000 != 0) permissions += PosixFilePermission.OWNER_WRITE
            if (mode and 0b001_000_000 != 0) permissions += PosixFilePermission.OWNER_EXECUTE
            if (mode and 0b000_100_000 != 0) permissions += PosixFilePermission.GROUP_READ
            if (mode and 0b000_010_000 != 0) permissions += PosixFilePermission.GROUP_WRITE
            if (mode and 0b000_001_000 != 0) permissions += PosixFilePermission.GROUP_EXECUTE
            if (mode and 0b000_000_100 != 0) permissions += PosixFilePermission.OTHERS_READ
            if (mode and 0b000_000_010 != 0) permissions += PosixFilePermission.OTHERS_WRITE
            if (mode and 0b000_000_001 != 0) permissions += PosixFilePermission.OTHERS_EXECUTE

            try {
                Files.setPosixFilePermissions(path, permissions)
            } catch (_: UnsupportedOperationException) {
                // Windows and some non-POSIX filesystems do not support permission attributes.
            }
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        }

        private fun normalizeBundleDir(directory: File, platform: String): File? {
            val candidates = listOf(
                directory,
                File(directory, "gdal"),
                File(directory, platform),
                File(directory, "$platform/gdal"),
                File(File(directory, "gdal"), platform),
                File(File(directory, platform), "gdal"),
            )

            return candidates.firstOrNull { isBundleLayout(it) }
        }

        private fun stringVector(values: List<String>): Vector<String> {
            return Vector<String>().apply {
                values.forEach { add(it) }
            }
        }

        private fun debug(message: String) {
            if (java.lang.Boolean.getBoolean("sample-cli.debug") || java.lang.Boolean.getBoolean("gdal4k.debug")) {
                System.err.println("[gdal4k] $message")
            }
        }

        private fun isBundleLayout(directory: File): Boolean {
            if (!directory.exists() || !directory.isDirectory) {
                return false
            }

            val nativeLibraryName = System.mapLibraryName("gdalalljni")
            return File(directory, "lib/$nativeLibraryName").exists()
        }
    }
}

private class JvmDataset(
    val delegate: NativeDataset,
) : Dataset {
    @Volatile
    private var closed = false

    override fun close() {
        if (closed) {
            return
        }
        synchronized(this) {
            if (!closed) {
                delegate.delete()
                closed = true
            }
        }
    }

    override fun flushCache(): Int {
        return delegate.FlushCache()
    }

    override fun getDriver(): Driver? {
        return delegate.GetDriver()?.let(::JvmDriver)
    }

    override fun getDriverShortName(): String {
        return delegate.GetDriver()?.getShortName().orEmpty()
    }

    override fun getDriverLongName(): String {
        return delegate.GetDriver()?.getLongName().orEmpty()
    }

    override fun getRasterXSize(): Int {
        return delegate.GetRasterXSize()
    }

    override fun getRasterYSize(): Int {
        return delegate.GetRasterYSize()
    }

    override fun getRasterCount(): Int {
        return delegate.GetRasterCount()
    }

    override fun getLayerCount(): Int {
        return delegate.GetLayerCount()
    }

    override fun getLayer(index: Int): Layer? {
        return delegate.GetLayer(index)?.let(::JvmLayer)
    }

    override fun getLayer(name: String): Layer? {
        return delegate.GetLayer(name)?.let(::JvmLayer)
    }

    override fun getRasterBand(index: Int): Band? {
        return delegate.GetRasterBand(index)?.let(::JvmBand)
    }

    override fun getProjection(): String {
        return delegate.GetProjection()
    }

    override fun getProjectionRef(): String {
        return delegate.GetProjectionRef()
    }

    override fun setProjection(projectionWkt: String): Int {
        return delegate.SetProjection(projectionWkt)
    }

    override fun getGeoTransform(): DoubleArray? {
        return delegate.GetGeoTransform()
    }

    override fun setGeoTransform(geoTransform: DoubleArray): Int {
        return delegate.SetGeoTransform(geoTransform)
    }

    override fun getExtent(): DoubleArray? {
        val extent = DoubleArray(4)
        return if (delegate.GetExtent(extent) == 0) {
            extent
        } else {
            null
        }
    }

    override fun getGCPCount(): Int {
        return delegate.GetGCPCount()
    }

    override fun getGCPProjection(): String {
        return delegate.GetGCPProjection()
    }

    override fun getGCPSpatialRef(): SpatialReference? {
        return delegate.GetGCPSpatialRef()?.let(::JvmSpatialReference)
    }

    override fun getGCPs(): List<GCP> {
        val gcps = Vector<NativeGCP>()
        delegate.GetGCPs(gcps)
        return gcps.map { gcp -> JvmGCP(gcp) }
    }

    override fun setGCPs(gcps: List<GCP>, projection: String): Int {
        return delegate.SetGCPs(nativeGcps(gcps), projection)
    }

    override fun setGCPs(gcps: List<GCP>, spatialReference: SpatialReference): Int {
        val nativeSpatialReference = (spatialReference as? JvmSpatialReference)?.delegate
            ?: throw IllegalArgumentException("SpatialReference was not created by the JVM GDAL runtime.")
        return delegate.SetGCPs2(nativeGcps(gcps), nativeSpatialReference)
    }

    override fun getFileList(): List<String> {
        return delegate.GetFileList().map { it.toString() }
    }

    override fun buildOverviews(resampling: String, overviewBands: IntArray): Int {
        return delegate.BuildOverviews(resampling, overviewBands)
    }

    override fun resetReading() {
        delegate.ResetReading()
    }

    override fun testCapability(capability: String): Boolean {
        return delegate.TestCapability(capability)
    }
}

private class JvmDriver(
    val delegate: NativeDriver,
) : Driver {
    @Volatile
    private var closed = false

    override fun close() {
        if (closed) {
            return
        }
        synchronized(this) {
            if (!closed) {
                delegate.delete()
                closed = true
            }
        }
    }

    override fun getShortName(): String {
        return delegate.getShortName()
    }

    override fun getLongName(): String {
        return delegate.getLongName()
    }

    override fun getHelpTopic(): String {
        return delegate.getHelpTopic()
    }

    override fun create(
        name: String,
        xSize: Int,
        ySize: Int,
        bandCount: Int,
        dataType: Int,
        options: List<String>,
    ): Dataset? {
        val dataset = if (options.isNotEmpty()) {
            delegate.Create(name, xSize, ySize, bandCount, dataType, stringVector(options))
        } else {
            delegate.Create(name, xSize, ySize, bandCount, dataType)
        }
        return dataset?.let(::JvmDataset)
    }

    override fun createCopy(
        name: String,
        dataset: Dataset,
        strict: Int,
        options: List<String>,
    ): Dataset? {
        val nativeDataset = (dataset as? JvmDataset)?.delegate
            ?: throw IllegalArgumentException("Dataset was not created by the JVM GDAL runtime.")
        val copy = if (options.isNotEmpty()) {
            delegate.CreateCopy(name, nativeDataset, strict, stringVector(options))
        } else {
            delegate.CreateCopy(name, nativeDataset, strict)
        }
        return copy?.let(::JvmDataset)
    }

    override fun createVector(
        name: String,
        options: List<String>,
    ): Dataset? {
        val dataset = if (options.isNotEmpty()) {
            delegate.CreateVector(name, stringVector(options))
        } else {
            delegate.CreateVector(name)
        }
        return dataset?.let(::JvmDataset)
    }

    override fun delete(name: String): Int {
        return delegate.Delete(name)
    }

    override fun rename(from: String, to: String): Int {
        return delegate.Rename(from, to)
    }

    override fun copyFiles(from: String, to: String): Int {
        return delegate.CopyFiles(from, to)
    }

    override fun hasOpenOption(option: String): Boolean {
        return delegate.HasOpenOption(option)
    }

    override fun register(): Int {
        return delegate.Register()
    }

    override fun deregister() {
        delegate.Deregister()
    }
}

private class JvmGCP(
    val delegate: NativeGCP,
) : GCP {
    @Volatile
    private var closed = false

    override fun close() {
        if (closed) {
            return
        }
        synchronized(this) {
            if (!closed) {
                delegate.delete()
                closed = true
            }
        }
    }

    override fun getGCPX(): Double {
        return delegate.getGCPX()
    }

    override fun setGCPX(value: Double) {
        delegate.setGCPX(value)
    }

    override fun getGCPY(): Double {
        return delegate.getGCPY()
    }

    override fun setGCPY(value: Double) {
        delegate.setGCPY(value)
    }

    override fun getGCPZ(): Double {
        return delegate.getGCPZ()
    }

    override fun setGCPZ(value: Double) {
        delegate.setGCPZ(value)
    }

    override fun getGCPPixel(): Double {
        return delegate.getGCPPixel()
    }

    override fun setGCPPixel(value: Double) {
        delegate.setGCPPixel(value)
    }

    override fun getGCPLine(): Double {
        return delegate.getGCPLine()
    }

    override fun setGCPLine(value: Double) {
        delegate.setGCPLine(value)
    }

    override fun getInfo(): String {
        return delegate.getInfo()
    }

    override fun setInfo(info: String) {
        delegate.setInfo(info)
    }

    override fun getId(): String {
        return delegate.getId()
    }

    override fun setId(id: String) {
        delegate.setId(id)
    }
}

private class JvmSpatialReference(
    val delegate: NativeSpatialReference,
) : SpatialReference {
    @Volatile
    private var closed = false

    override fun close() {
        if (closed) {
            return
        }
        synchronized(this) {
            if (!closed) {
                delegate.delete()
                closed = true
            }
        }
    }

    override fun cloneSpatialReference(): SpatialReference {
        return JvmSpatialReference(delegate.Clone())
    }

    override fun exportToWkt(): String {
        return delegate.ExportToWkt()
    }

    override fun exportToPrettyWkt(): String {
        return delegate.ExportToPrettyWkt()
    }

    override fun exportToProj4(): String {
        return delegate.ExportToProj4()
    }

    override fun exportToProjJson(): String {
        val output = arrayOfNulls<String>(1)
        val result = delegate.ExportToPROJJSON(output)
        if (result != 0) {
            throw IllegalStateException("Unable to export spatial reference to PROJJSON.")
        }
        return output[0].orEmpty()
    }

    override fun exportToXml(rootElement: String): String {
        return if (rootElement.isBlank()) {
            delegate.ExportToXML()
        } else {
            delegate.ExportToXML(rootElement)
        }
    }

    override fun exportToMICoordSys(): String {
        return delegate.ExportToMICoordSys()
    }

    override fun importFromWkt(wkt: String): Int {
        return delegate.ImportFromWkt(wkt)
    }

    override fun importFromProj4(proj4: String): Int {
        return delegate.ImportFromProj4(proj4)
    }

    override fun importFromEPSG(epsg: Int): Int {
        return delegate.ImportFromEPSG(epsg)
    }

    override fun importFromEPSGA(epsga: Int): Int {
        return delegate.ImportFromEPSGA(epsga)
    }

    override fun setFromUserInput(definition: String): Int {
        return delegate.SetFromUserInput(definition)
    }

    override fun setWellKnownGeogCS(name: String): Int {
        return delegate.SetWellKnownGeogCS(name)
    }

    override fun setProjection(name: String): Int {
        return delegate.SetProjection(name)
    }

    override fun setProjParm(name: String, value: Double): Int {
        return delegate.SetProjParm(name, value)
    }

    override fun getProjParm(name: String, defaultValue: Double): Double {
        return delegate.GetProjParm(name, defaultValue)
    }

    override fun setNormProjParm(name: String, value: Double): Int {
        return delegate.SetNormProjParm(name, value)
    }

    override fun getNormProjParm(name: String, defaultValue: Double): Double {
        return delegate.GetNormProjParm(name, defaultValue)
    }

    override fun getName(): String {
        return delegate.GetName()
    }

    override fun getCelestialBodyName(): String {
        return delegate.GetCelestialBodyName()
    }

    override fun isSame(other: SpatialReference): Boolean {
        val nativeOther = (other as? JvmSpatialReference)?.delegate
            ?: throw IllegalArgumentException("SpatialReference was not created by the JVM GDAL runtime.")
        return delegate.IsSame(nativeOther) != 0
    }

    override fun isGeographic(): Boolean {
        return delegate.IsGeographic() != 0
    }

    override fun isProjected(): Boolean {
        return delegate.IsProjected() != 0
    }

    override fun isGeocentric(): Boolean {
        return delegate.IsGeocentric() != 0
    }

    override fun isCompound(): Boolean {
        return delegate.IsCompound() != 0
    }

    override fun isVertical(): Boolean {
        return delegate.IsVertical() != 0
    }

    override fun isLocal(): Boolean {
        return delegate.IsLocal() != 0
    }

    override fun isDynamic(): Boolean {
        return delegate.IsDynamic()
    }

    override fun hasPointMotionOperation(): Boolean {
        return delegate.HasPointMotionOperation()
    }

    override fun getCoordinateEpoch(): Double {
        return delegate.GetCoordinateEpoch()
    }

    override fun setCoordinateEpoch(epoch: Double) {
        delegate.SetCoordinateEpoch(epoch)
    }

    override fun getAuthorityName(targetKey: String): String? {
        return delegate.GetAuthorityName(targetKey)
    }

    override fun getAuthorityCode(targetKey: String): String? {
        return delegate.GetAuthorityCode(targetKey)
    }

    override fun getAttrValue(name: String, child: Int): String? {
        return delegate.GetAttrValue(name, child)
    }

    override fun setAttrValue(name: String, value: String): Int {
        return delegate.SetAttrValue(name, value)
    }

    override fun setAuthority(targetKey: String, authority: String, code: Int): Int {
        return delegate.SetAuthority(targetKey, authority, code)
    }

    override fun getAngularUnits(): Double {
        return delegate.GetAngularUnits()
    }

    override fun getAngularUnitsName(): String {
        return delegate.GetAngularUnitsName()
    }

    override fun setAngularUnits(name: String, radiansPerUnit: Double): Int {
        return delegate.SetAngularUnits(name, radiansPerUnit)
    }

    override fun getLinearUnits(): Double {
        return delegate.GetLinearUnits()
    }

    override fun getLinearUnitsName(): String {
        return delegate.GetLinearUnitsName()
    }

    override fun setLinearUnits(name: String, metersPerUnit: Double): Int {
        return delegate.SetLinearUnits(name, metersPerUnit)
    }

    override fun setLinearUnitsAndUpdateParameters(name: String, metersPerUnit: Double): Int {
        return delegate.SetLinearUnitsAndUpdateParameters(name, metersPerUnit)
    }

    override fun getAxisName(targetKey: String, index: Int): String? {
        return delegate.GetAxisName(targetKey, index)
    }

    override fun getAxesCount(): Int {
        return delegate.GetAxesCount()
    }

    override fun getAxisOrientation(targetKey: String, index: Int): Int {
        return delegate.GetAxisOrientation(targetKey, index)
    }

    override fun getAxisMappingStrategy(): Int {
        return delegate.GetAxisMappingStrategy()
    }

    override fun setAxisMappingStrategy(strategy: Int) {
        delegate.SetAxisMappingStrategy(strategy)
    }

    override fun setUTM(zone: Int, north: Int): Int {
        return delegate.SetUTM(zone, north)
    }

    override fun getUTMZone(): Int {
        return delegate.GetUTMZone()
    }

    override fun autoIdentifyEPSG(): Int {
        return delegate.AutoIdentifyEPSG()
    }

    override fun validate(): Int {
        return delegate.Validate()
    }

    override fun morphToESRI(): Int {
        return delegate.MorphToESRI()
    }

    override fun morphFromESRI(): Int {
        return delegate.MorphFromESRI()
    }

    override fun stripVertical(): Int {
        return delegate.StripVertical()
    }

    override fun cloneGeogCS(): SpatialReference? {
        return delegate.CloneGeogCS()?.let(::JvmSpatialReference)
    }

    override fun convertToOtherProjection(projection: String): SpatialReference? {
        return delegate.ConvertToOtherProjection(projection)?.let(::JvmSpatialReference)
    }

    override fun promoteTo3D(): Int {
        return delegate.PromoteTo3D()
    }

    override fun demoteTo2D(): Int {
        return delegate.DemoteTo2D()
    }
}

private class JvmCoordinateTransformation(
    val delegate: NativeCoordinateTransformation,
) : CoordinateTransformation {
    @Volatile
    private var closed = false

    override fun close() {
        if (closed) {
            return
        }
        synchronized(this) {
            if (!closed) {
                delegate.delete()
                closed = true
            }
        }
    }

    override fun transformPoint(x: Double, y: Double): DoubleArray {
        return delegate.TransformPoint(x, y)
    }

    override fun transformPoint(x: Double, y: Double, z: Double): DoubleArray {
        return delegate.TransformPoint(x, y, z)
    }

    override fun transformPoints(points: Array<DoubleArray>) {
        delegate.TransformPoints(points)
    }

    override fun transformBounds(
        bounds: DoubleArray,
        xMin: Double,
        yMin: Double,
        xMax: Double,
        yMax: Double,
        densifyPoints: Int,
    ) {
        delegate.TransformBounds(bounds, xMin, yMin, xMax, yMax, densifyPoints)
    }

    override fun getInverse(): CoordinateTransformation? {
        return delegate.GetInverse()?.let(::JvmCoordinateTransformation)
    }
}

private class JvmLayer(
    val delegate: NativeLayer,
) : Layer {
    @Volatile
    private var closed = false

    override fun close() {
        if (closed) {
            return
        }
        synchronized(this) {
            if (!closed) {
                delegate.delete()
                closed = true
            }
        }
    }

    override fun getDataset(): Dataset? {
        return delegate.GetDataset()?.let(::JvmDataset)
    }

    override fun getLayerDefn(): FeatureDefn? {
        return delegate.GetLayerDefn()?.let(::JvmFeatureDefn)
    }

    override fun getName(): String {
        return delegate.GetName()
    }

    override fun getGeomType(): Int {
        return delegate.GetGeomType()
    }

    override fun getGeometryColumn(): String {
        return delegate.GetGeometryColumn()
    }

    override fun getFIDColumn(): String {
        return delegate.GetFIDColumn()
    }

    override fun rename(name: String): Int {
        return delegate.Rename(name)
    }

    override fun getRefCount(): Int {
        return delegate.GetRefCount()
    }

    override fun createGeomField(geomFieldDefn: GeomFieldDefn, flags: Int): Int {
        val nativeGeomFieldDefn = (geomFieldDefn as? JvmGeomFieldDefn)?.delegate
            ?: throw IllegalArgumentException("GeomFieldDefn was not created by the JVM GDAL runtime.")
        return if (flags == 0) {
            delegate.CreateGeomField(nativeGeomFieldDefn)
        } else {
            delegate.CreateGeomField(nativeGeomFieldDefn, flags)
        }
    }

    override fun setAttributeFilter(filter: String): Int {
        return delegate.SetAttributeFilter(filter)
    }

    override fun resetReading() {
        delegate.ResetReading()
    }

    override fun getFeature(fid: Long): Feature? {
        return delegate.GetFeature(fid)?.let(::JvmFeature)
    }

    override fun getNextFeature(): Feature? {
        return delegate.GetNextFeature()?.let(::JvmFeature)
    }

    override fun createFeature(feature: Feature): Int {
        val nativeFeature = (feature as? JvmFeature)?.delegate
            ?: throw IllegalArgumentException("Feature was not created by the JVM GDAL runtime.")
        return delegate.CreateFeature(nativeFeature)
    }

    override fun setFeature(feature: Feature): Int {
        val nativeFeature = (feature as? JvmFeature)?.delegate
            ?: throw IllegalArgumentException("Feature was not created by the JVM GDAL runtime.")
        return delegate.SetFeature(nativeFeature)
    }

    override fun upsertFeature(feature: Feature): Int {
        val nativeFeature = (feature as? JvmFeature)?.delegate
            ?: throw IllegalArgumentException("Feature was not created by the JVM GDAL runtime.")
        return delegate.UpsertFeature(nativeFeature)
    }

    override fun deleteFeature(fid: Long): Int {
        return delegate.DeleteFeature(fid)
    }

    override fun setNextByIndex(index: Long): Int {
        return delegate.SetNextByIndex(index)
    }

    override fun getFeatureCount(force: Boolean): Long {
        return if (force) delegate.GetFeatureCount(1) else delegate.GetFeatureCount()
    }

    override fun getExtent(force: Boolean): DoubleArray? {
        val extent = DoubleArray(4)
        return if (delegate.GetExtent(extent, if (force) 1 else 0) == 0) {
            extent
        } else {
            null
        }
    }

    override fun getFeaturesRead(): Long {
        return delegate.GetFeaturesRead()
    }

    override fun syncToDisk(): Int {
        return delegate.SyncToDisk()
    }

    override fun testCapability(capability: String): Boolean {
        return delegate.TestCapability(capability)
    }
}

private class JvmFeatureDefn(
    val delegate: NativeFeatureDefn,
) : FeatureDefn {
    @Volatile
    private var closed = false

    override fun close() {
        if (closed) {
            return
        }
        synchronized(this) {
            if (!closed) {
                delegate.delete()
                closed = true
            }
        }
    }

    override fun getName(): String {
        return delegate.GetName()
    }

    override fun getFieldCount(): Int {
        return delegate.GetFieldCount()
    }

    override fun getFieldDefn(index: Int): FieldDefn? {
        return delegate.GetFieldDefn(index)?.let(::JvmFieldDefn)
    }

    override fun getFieldIndex(name: String): Int {
        return delegate.GetFieldIndex(name)
    }

    override fun addFieldDefn(fieldDefn: FieldDefn) {
        val nativeFieldDefn = (fieldDefn as? JvmFieldDefn)?.delegate
            ?: throw IllegalArgumentException("FieldDefn was not created by the JVM GDAL runtime.")
        delegate.AddFieldDefn(nativeFieldDefn)
    }

    override fun getGeomFieldCount(): Int {
        return delegate.GetGeomFieldCount()
    }

    override fun getGeomFieldDefn(index: Int): GeomFieldDefn? {
        return delegate.GetGeomFieldDefn(index)?.let(::JvmGeomFieldDefn)
    }

    override fun getGeomFieldIndex(name: String): Int {
        return delegate.GetGeomFieldIndex(name)
    }

    override fun addGeomFieldDefn(geomFieldDefn: GeomFieldDefn) {
        val nativeGeomFieldDefn = (geomFieldDefn as? JvmGeomFieldDefn)?.delegate
            ?: throw IllegalArgumentException("GeomFieldDefn was not created by the JVM GDAL runtime.")
        delegate.AddGeomFieldDefn(nativeGeomFieldDefn)
    }

    override fun getGeomType(): Int {
        return delegate.GetGeomType()
    }

    override fun setGeomType(geomType: Int) {
        delegate.SetGeomType(geomType)
    }

    override fun getReferenceCount(): Int {
        return delegate.GetReferenceCount()
    }

    override fun isGeometryIgnored(): Boolean {
        return delegate.IsGeometryIgnored() != 0
    }

    override fun setGeometryIgnored(ignored: Boolean) {
        delegate.SetGeometryIgnored(if (ignored) 1 else 0)
    }

    override fun isStyleIgnored(): Boolean {
        return delegate.IsStyleIgnored() != 0
    }

    override fun setStyleIgnored(ignored: Boolean) {
        delegate.SetStyleIgnored(if (ignored) 1 else 0)
    }

    override fun isSame(other: FeatureDefn): Boolean {
        val nativeOther = (other as? JvmFeatureDefn)?.delegate
            ?: throw IllegalArgumentException("FeatureDefn was not created by the JVM GDAL runtime.")
        return delegate.IsSame(nativeOther) != 0
    }
}

private class JvmGeomFieldDefn(
    val delegate: NativeGeomFieldDefn,
) : GeomFieldDefn {
    @Volatile
    private var closed = false

    override fun close() {
        if (closed) {
            return
        }
        synchronized(this) {
            if (!closed) {
                delegate.delete()
                closed = true
            }
        }
    }

    override fun getName(): String {
        return delegate.GetName()
    }

    override fun getNameRef(): String {
        return delegate.GetNameRef()
    }

    override fun setName(name: String) {
        delegate.SetName(name)
    }

    override fun getType(): Int {
        return delegate.GetType()
    }

    override fun setType(type: Int) {
        delegate.SetType(type)
    }

    override fun isIgnored(): Boolean {
        return delegate.IsIgnored() != 0
    }

    override fun setIgnored(ignored: Boolean) {
        delegate.SetIgnored(if (ignored) 1 else 0)
    }

    override fun isNullable(): Boolean {
        return delegate.IsNullable() != 0
    }

    override fun setNullable(nullable: Boolean) {
        delegate.SetNullable(if (nullable) 1 else 0)
    }
}

private class JvmFieldDefn(
    val delegate: NativeFieldDefn,
) : FieldDefn {
    @Volatile
    private var closed = false

    override fun close() {
        if (closed) {
            return
        }
        synchronized(this) {
            if (!closed) {
                delegate.delete()
                closed = true
            }
        }
    }

    override fun getName(): String {
        return delegate.GetName()
    }

    override fun getNameRef(): String {
        return delegate.GetNameRef()
    }

    override fun setName(name: String) {
        delegate.SetName(name)
    }

    override fun getAlternativeName(): String {
        return delegate.GetAlternativeName()
    }

    override fun getAlternativeNameRef(): String {
        return delegate.GetAlternativeNameRef()
    }

    override fun setAlternativeName(name: String) {
        delegate.SetAlternativeName(name)
    }

    override fun getType(): Int {
        return delegate.GetType()
    }

    override fun setType(type: Int) {
        delegate.SetType(type)
    }

    override fun getFieldType(): Int {
        return delegate.GetFieldType()
    }

    override fun getSubType(): Int {
        return delegate.GetSubType()
    }

    override fun setSubType(subType: Int) {
        delegate.SetSubType(subType)
    }

    override fun getJustify(): Int {
        return delegate.GetJustify()
    }

    override fun setJustify(justify: Int) {
        delegate.SetJustify(justify)
    }

    override fun getWidth(): Int {
        return delegate.GetWidth()
    }

    override fun setWidth(width: Int) {
        delegate.SetWidth(width)
    }

    override fun getPrecision(): Int {
        return delegate.GetPrecision()
    }

    override fun setPrecision(precision: Int) {
        delegate.SetPrecision(precision)
    }

    override fun getTZFlag(): Int {
        return delegate.GetTZFlag()
    }

    override fun setTZFlag(flag: Int) {
        delegate.SetTZFlag(flag)
    }

    override fun getTypeName(): String {
        return delegate.GetTypeName()
    }

    override fun getFieldTypeName(type: Int): String {
        return delegate.GetFieldTypeName(type)
    }

    override fun isIgnored(): Boolean {
        return delegate.IsIgnored() != 0
    }

    override fun setIgnored(ignored: Boolean) {
        delegate.SetIgnored(if (ignored) 1 else 0)
    }

    override fun isNullable(): Boolean {
        return delegate.IsNullable() != 0
    }

    override fun setNullable(nullable: Boolean) {
        delegate.SetNullable(if (nullable) 1 else 0)
    }

    override fun isUnique(): Boolean {
        return delegate.IsUnique() != 0
    }

    override fun setUnique(unique: Boolean) {
        delegate.SetUnique(if (unique) 1 else 0)
    }

    override fun isGenerated(): Boolean {
        return delegate.IsGenerated() != 0
    }

    override fun setGenerated(generated: Boolean) {
        delegate.SetGenerated(if (generated) 1 else 0)
    }

    override fun getDefault(): String {
        return delegate.GetDefault()
    }

    override fun setDefault(defaultValue: String) {
        delegate.SetDefault(defaultValue)
    }

    override fun isDefaultDriverSpecific(): Boolean {
        return delegate.IsDefaultDriverSpecific() != 0
    }

    override fun getDomainName(): String {
        return delegate.GetDomainName()
    }

    override fun getDomainNameRef(): String {
        return delegate.GetDomainName()
    }

    override fun setDomainName(domainName: String) {
        delegate.SetDomainName(domainName)
    }

    override fun getComment(): String {
        return delegate.GetComment()
    }

    override fun getCommentRef(): String {
        return delegate.GetComment()
    }

    override fun setComment(comment: String) {
        delegate.SetComment(comment)
    }
}

private class JvmFeature(
    val delegate: NativeFeature,
) : Feature {
    @Volatile
    private var closed = false

    override fun close() {
        if (closed) {
            return
        }
        synchronized(this) {
            if (!closed) {
                delegate.delete()
                closed = true
            }
        }
    }

    override fun getDefinition(): FeatureDefn {
        return JvmFeatureDefn(delegate.GetDefnRef())
    }

    override fun cloneFeature(): Feature {
        return JvmFeature(delegate.Clone())
    }

    override fun equal(other: Feature): Boolean {
        val nativeOther = (other as? JvmFeature)?.delegate
            ?: throw IllegalArgumentException("Feature was not created by the JVM GDAL runtime.")
        return delegate.Equal(nativeOther)
    }

    override fun getFID(): Long {
        return delegate.GetFID()
    }

    override fun setFID(fid: Long): Int {
        return delegate.SetFID(fid)
    }

    override fun getGeometryRef(): Geometry? {
        return delegate.GetGeometryRef()?.let(::JvmGeometry)
    }

    override fun setGeometry(geometry: Geometry): Int {
        val nativeGeometry = (geometry as? JvmGeometry)?.delegate
            ?: throw IllegalArgumentException("Geometry was not created by the JVM GDAL runtime.")
        return delegate.SetGeometry(nativeGeometry)
    }

    override fun setGeometryDirectly(geometry: Geometry): Int {
        val nativeGeometry = (geometry as? JvmGeometry)?.delegate
            ?: throw IllegalArgumentException("Geometry was not created by the JVM GDAL runtime.")
        return delegate.SetGeometryDirectly(nativeGeometry)
    }

    override fun getFieldCount(): Int {
        return delegate.GetFieldCount()
    }

    override fun getFieldDefn(index: Int): FieldDefn? {
        return delegate.GetFieldDefnRef(index)?.let(::JvmFieldDefn)
    }

    override fun getFieldDefn(name: String): FieldDefn? {
        return delegate.GetFieldDefnRef(name)?.let(::JvmFieldDefn)
    }

    override fun getGeomFieldCount(): Int {
        return delegate.GetGeomFieldCount()
    }

    override fun getGeomFieldDefn(index: Int): GeomFieldDefn? {
        return delegate.GetGeomFieldDefnRef(index)?.let(::JvmGeomFieldDefn)
    }

    override fun getGeomFieldDefn(name: String): GeomFieldDefn? {
        return delegate.GetGeomFieldDefnRef(name)?.let(::JvmGeomFieldDefn)
    }

    override fun getGeomFieldIndex(name: String): Int {
        return delegate.GetGeomFieldIndex(name)
    }

    override fun getGeomFieldRef(index: Int): Geometry? {
        return delegate.GetGeomFieldRef(index)?.let(::JvmGeometry)
    }

    override fun getGeomFieldRef(name: String): Geometry? {
        return delegate.GetGeomFieldRef(name)?.let(::JvmGeometry)
    }

    override fun setGeomField(index: Int, geometry: Geometry): Int {
        val nativeGeometry = (geometry as? JvmGeometry)?.delegate
            ?: throw IllegalArgumentException("Geometry was not created by the JVM GDAL runtime.")
        return delegate.SetGeomField(index, nativeGeometry)
    }

    override fun setGeomField(name: String, geometry: Geometry): Int {
        val nativeGeometry = (geometry as? JvmGeometry)?.delegate
            ?: throw IllegalArgumentException("Geometry was not created by the JVM GDAL runtime.")
        return delegate.SetGeomField(name, nativeGeometry)
    }

    override fun setGeomFieldDirectly(index: Int, geometry: Geometry): Int {
        val nativeGeometry = (geometry as? JvmGeometry)?.delegate
            ?: throw IllegalArgumentException("Geometry was not created by the JVM GDAL runtime.")
        return delegate.SetGeomFieldDirectly(index, nativeGeometry)
    }

    override fun setGeomFieldDirectly(name: String, geometry: Geometry): Int {
        val nativeGeometry = (geometry as? JvmGeometry)?.delegate
            ?: throw IllegalArgumentException("Geometry was not created by the JVM GDAL runtime.")
        return delegate.SetGeomFieldDirectly(name, nativeGeometry)
    }

    override fun getFieldAsString(index: Int): String {
        return delegate.GetFieldAsString(index)
    }

    override fun getFieldAsString(name: String): String {
        return delegate.GetFieldAsString(name)
    }

    override fun getFieldAsInteger(index: Int): Int {
        return delegate.GetFieldAsInteger(index)
    }

    override fun getFieldAsInteger(name: String): Int {
        return delegate.GetFieldAsInteger(name)
    }

    override fun getFieldAsInteger64(index: Int): Long {
        return delegate.GetFieldAsInteger64(index)
    }

    override fun getFieldAsInteger64(name: String): Long {
        return delegate.GetFieldAsInteger64(name)
    }

    override fun getFieldAsDouble(index: Int): Double {
        return delegate.GetFieldAsDouble(index)
    }

    override fun getFieldAsDouble(name: String): Double {
        return delegate.GetFieldAsDouble(name)
    }

    override fun getFieldAsStringList(index: Int): List<String> {
        return delegate.GetFieldAsStringList(index).map { it.toString() }
    }

    override fun getFieldAsIntegerList(index: Int): IntArray {
        return delegate.GetFieldAsIntegerList(index)
    }

    override fun getFieldAsDoubleList(index: Int): DoubleArray {
        return delegate.GetFieldAsDoubleList(index)
    }

    override fun getFieldAsBinary(index: Int): ByteArray {
        return delegate.GetFieldAsBinary(index)
    }

    override fun isFieldSet(index: Int): Boolean {
        return delegate.IsFieldSet(index)
    }

    override fun isFieldSet(name: String): Boolean {
        return delegate.IsFieldSet(name)
    }

    override fun isFieldNull(index: Int): Boolean {
        return delegate.IsFieldNull(index)
    }

    override fun isFieldNull(name: String): Boolean {
        return delegate.IsFieldNull(name)
    }

    override fun isFieldSetAndNotNull(index: Int): Boolean {
        return delegate.IsFieldSetAndNotNull(index)
    }

    override fun isFieldSetAndNotNull(name: String): Boolean {
        return delegate.IsFieldSetAndNotNull(name)
    }

    override fun getFieldIndex(name: String): Int {
        return delegate.GetFieldIndex(name)
    }

    override fun dumpReadableAsString(): String {
        return delegate.DumpReadableAsString()
    }

    override fun getStyleString(): String {
        return delegate.GetStyleString()
    }

    override fun setStyleString(style: String) {
        delegate.SetStyleString(style)
    }

    override fun getFieldType(index: Int): Int {
        return delegate.GetFieldType(index)
    }

    override fun getFieldType(name: String): Int {
        return delegate.GetFieldType(name)
    }

    override fun validate(flags: Int, options: Int): Int {
        return delegate.Validate(flags, options)
    }

    override fun fillUnsetWithDefault() {
        delegate.FillUnsetWithDefault()
    }

    override fun getNativeData(): String {
        return delegate.GetNativeData()
    }

    override fun getNativeMediaType(): String {
        return delegate.GetNativeMediaType()
    }

    override fun setNativeData(nativeData: String) {
        delegate.SetNativeData(nativeData)
    }

    override fun setNativeMediaType(nativeMediaType: String) {
        delegate.SetNativeMediaType(nativeMediaType)
    }

    override fun unsetField(index: Int) {
        delegate.UnsetField(index)
    }

    override fun unsetField(name: String) {
        delegate.UnsetField(name)
    }

    override fun setFieldNull(index: Int) {
        delegate.SetFieldNull(index)
    }

    override fun setFieldNull(name: String) {
        delegate.SetFieldNull(name)
    }

    override fun setField(index: Int, value: String) {
        delegate.SetField(index, value)
    }

    override fun setField(name: String, value: String) {
        delegate.SetField(name, value)
    }

    override fun setField(index: Int, value: Int) {
        delegate.SetField(index, value)
    }

    override fun setField(name: String, value: Int) {
        delegate.SetField(name, value)
    }

    override fun setFieldInteger64(index: Int, value: Long) {
        delegate.SetFieldInteger64(index, value)
    }

    override fun setField(index: Int, value: Double) {
        delegate.SetField(index, value)
    }

    override fun setField(name: String, value: Double) {
        delegate.SetField(name, value)
    }
}

private class JvmGeometry(
    val delegate: NativeGeometry,
) : Geometry {
    @Volatile
    private var closed = false

    override fun close() {
        if (closed) {
            return
        }
        synchronized(this) {
            if (!closed) {
                delegate.delete()
                closed = true
            }
        }
    }

    override fun cloneGeometry(): Geometry {
        return JvmGeometry(delegate.Clone())
    }

    override fun getGeometryType(): Int {
        return delegate.GetGeometryType()
    }

    override fun getGeometryName(): String {
        return delegate.GetGeometryName()
    }

    override fun getPointCount(): Int {
        return delegate.GetPointCount()
    }

    override fun getGeometryCount(): Int {
        return delegate.GetGeometryCount()
    }

    override fun getPoint(index: Int): DoubleArray {
        return delegate.GetPoint(index)
    }

    override fun getPoints(): List<DoubleArray> {
        return delegate.GetPoints().map { it.clone() }
    }

    override fun getGeometryRef(index: Int): Geometry? {
        return delegate.GetGeometryRef(index)?.let(::JvmGeometry)
    }

    override fun addPoint(x: Double, y: Double) {
        delegate.AddPoint(x, y)
    }

    override fun addPoint(x: Double, y: Double, z: Double) {
        delegate.AddPoint(x, y, z)
    }

    override fun addPointM(x: Double, y: Double, m: Double) {
        delegate.AddPointM(x, y, m)
    }

    override fun addPointZM(x: Double, y: Double, z: Double, m: Double) {
        delegate.AddPointZM(x, y, z, m)
    }

    override fun addPoint2D(x: Double, y: Double) {
        delegate.AddPoint_2D(x, y)
    }

    override fun addGeometryDirectly(geometry: Geometry): Int {
        val nativeGeometry = (geometry as? JvmGeometry)?.delegate
            ?: throw IllegalArgumentException("Geometry was not created by the JVM GDAL runtime.")
        return delegate.AddGeometryDirectly(nativeGeometry)
    }

    override fun addGeometry(geometry: Geometry): Int {
        val nativeGeometry = (geometry as? JvmGeometry)?.delegate
            ?: throw IllegalArgumentException("Geometry was not created by the JVM GDAL runtime.")
        return delegate.AddGeometry(nativeGeometry)
    }

    override fun removeGeometry(index: Int): Int {
        return delegate.RemoveGeometry(index)
    }

    override fun setPoint(index: Int, x: Double, y: Double) {
        delegate.SetPoint(index, x, y)
    }

    override fun setPoint(index: Int, x: Double, y: Double, z: Double) {
        delegate.SetPoint(index, x, y, z)
    }

    override fun setPointM(index: Int, x: Double, y: Double, m: Double) {
        delegate.SetPointM(index, x, y, m)
    }

    override fun setPointZM(index: Int, x: Double, y: Double, z: Double, m: Double) {
        delegate.SetPointZM(index, x, y, z, m)
    }

    override fun setPoint2D(index: Int, x: Double, y: Double) {
        delegate.SetPoint_2D(index, x, y)
    }

    override fun swapXY() {
        delegate.SwapXY()
    }

    override fun exportToWkt(): String {
        return delegate.ExportToWkt()
    }

    override fun exportToIsoWkt(): String {
        val wkt = arrayOfNulls<String>(1)
        delegate.ExportToIsoWkt(wkt)
        return wkt[0].orEmpty()
    }

    override fun exportToGml(): String {
        return delegate.ExportToGML()
    }

    override fun exportToKml(): String {
        return delegate.ExportToKML()
    }

    override fun exportToJson(): String {
        return delegate.ExportToJson()
    }

    override fun exportToWkb(): ByteArray {
        return delegate.ExportToWkb()
    }

    override fun exportToIsoWkb(): ByteArray {
        return delegate.ExportToIsoWkb()
    }

    override fun getArea(): Double {
        return delegate.GetArea()
    }

    override fun length(): Double {
        return delegate.Length()
    }

    override fun geodesicLength(): Double {
        return delegate.GeodesicLength()
    }

    override fun geodesicArea(): Double {
        return delegate.GeodesicArea()
    }

    override fun distance(other: Geometry): Double {
        val nativeOther = (other as? JvmGeometry)?.delegate
            ?: throw IllegalArgumentException("Geometry was not created by the JVM GDAL runtime.")
        return delegate.Distance(nativeOther)
    }

    override fun distance3D(other: Geometry): Double {
        val nativeOther = (other as? JvmGeometry)?.delegate
            ?: throw IllegalArgumentException("Geometry was not created by the JVM GDAL runtime.")
        return delegate.Distance3D(nativeOther)
    }

    override fun empty() {
        delegate.Empty()
    }

    override fun isEmpty(): Boolean {
        return delegate.IsEmpty()
    }

    override fun isValid(): Boolean {
        return delegate.IsValid()
    }

    override fun isSimple(): Boolean {
        return delegate.IsSimple()
    }

    override fun isRing(): Boolean {
        return delegate.IsRing()
    }

    override fun intersects(other: Geometry): Boolean {
        val nativeOther = (other as? JvmGeometry)?.delegate
            ?: throw IllegalArgumentException("Geometry was not created by the JVM GDAL runtime.")
        return delegate.Intersects(nativeOther)
    }

    override fun intersectsExact(other: Geometry): Boolean {
        val nativeOther = (other as? JvmGeometry)?.delegate
            ?: throw IllegalArgumentException("Geometry was not created by the JVM GDAL runtime.")
        return delegate.Intersect(nativeOther)
    }

    override fun equal(other: Geometry): Boolean {
        val nativeOther = (other as? JvmGeometry)?.delegate
            ?: throw IllegalArgumentException("Geometry was not created by the JVM GDAL runtime.")
        return delegate.Equal(nativeOther)
    }

    override fun disjoint(other: Geometry): Boolean {
        val nativeOther = (other as? JvmGeometry)?.delegate
            ?: throw IllegalArgumentException("Geometry was not created by the JVM GDAL runtime.")
        return delegate.Disjoint(nativeOther)
    }

    override fun touches(other: Geometry): Boolean {
        val nativeOther = (other as? JvmGeometry)?.delegate
            ?: throw IllegalArgumentException("Geometry was not created by the JVM GDAL runtime.")
        return delegate.Touches(nativeOther)
    }

    override fun crosses(other: Geometry): Boolean {
        val nativeOther = (other as? JvmGeometry)?.delegate
            ?: throw IllegalArgumentException("Geometry was not created by the JVM GDAL runtime.")
        return delegate.Crosses(nativeOther)
    }

    override fun within(other: Geometry): Boolean {
        val nativeOther = (other as? JvmGeometry)?.delegate
            ?: throw IllegalArgumentException("Geometry was not created by the JVM GDAL runtime.")
        return delegate.Within(nativeOther)
    }

    override fun contains(other: Geometry): Boolean {
        val nativeOther = (other as? JvmGeometry)?.delegate
            ?: throw IllegalArgumentException("Geometry was not created by the JVM GDAL runtime.")
        return delegate.Contains(nativeOther)
    }

    override fun overlaps(other: Geometry): Boolean {
        val nativeOther = (other as? JvmGeometry)?.delegate
            ?: throw IllegalArgumentException("Geometry was not created by the JVM GDAL runtime.")
        return delegate.Overlaps(nativeOther)
    }

    override fun getEnvelope(): DoubleArray {
        val envelope = DoubleArray(4)
        delegate.GetEnvelope(envelope)
        return envelope
    }

    override fun getEnvelope3D(): DoubleArray {
        val envelope = DoubleArray(6)
        delegate.GetEnvelope3D(envelope)
        return envelope
    }

    override fun centroid(): Geometry? {
        return delegate.Centroid()?.let(::JvmGeometry)
    }

    override fun pointOnSurface(): Geometry? {
        return delegate.PointOnSurface()?.let(::JvmGeometry)
    }

    override fun boundary(): Geometry? {
        return delegate.Boundary()?.let(::JvmGeometry)
    }

    override fun convexHull(): Geometry? {
        return delegate.ConvexHull()?.let(::JvmGeometry)
    }

    override fun buffer(distance: Double): Geometry? {
        return delegate.Buffer(distance)?.let(::JvmGeometry)
    }

    override fun buffer(distance: Double, options: List<String>): Geometry? {
        return delegate.Buffer(distance, stringVector(options))?.let(::JvmGeometry)
    }

    override fun simplify(tolerance: Double): Geometry? {
        return delegate.Simplify(tolerance)?.let(::JvmGeometry)
    }

    override fun simplifyPreserveTopology(tolerance: Double): Geometry? {
        return delegate.SimplifyPreserveTopology(tolerance)?.let(::JvmGeometry)
    }

    override fun makeValid(): Geometry? {
        return delegate.MakeValid()?.let(::JvmGeometry)
    }

    override fun normalize(): Geometry? {
        return delegate.Normalize()?.let(::JvmGeometry)
    }

    override fun closeRings() {
        delegate.CloseRings()
    }

    override fun flattenTo2D() {
        delegate.FlattenTo2D()
    }

    override fun segmentize(maxLength: Double) {
        delegate.Segmentize(maxLength)
    }

    override fun wkbSize(): Long {
        return delegate.WkbSize()
    }

    override fun getCoordinateDimension(): Int {
        return delegate.GetCoordinateDimension()
    }

    override fun setCoordinateDimension(dimension: Int) {
        delegate.SetCoordinateDimension(dimension)
    }

    override fun is3D(): Boolean {
        return delegate.Is3D() != 0
    }

    override fun isMeasured(): Boolean {
        return delegate.IsMeasured() != 0
    }

    override fun set3D(enabled: Boolean) {
        delegate.Set3D(if (enabled) 1 else 0)
    }

    override fun setMeasured(enabled: Boolean) {
        delegate.SetMeasured(if (enabled) 1 else 0)
    }

    override fun getDimension(): Int {
        return delegate.GetDimension()
    }

    override fun hasCurveGeometry(): Boolean {
        return delegate.HasCurveGeometry() != 0
    }

    override fun getLinearGeometry(): Geometry? {
        return delegate.GetLinearGeometry()?.let(::JvmGeometry)
    }

    override fun getCurveGeometry(): Geometry? {
        return delegate.GetCurveGeometry()?.let(::JvmGeometry)
    }
}

private class JvmBand(
    val delegate: NativeBand,
) : Band {
    @Volatile
    private var closed = false

    override fun close() {
        if (closed) {
            return
        }
        synchronized(this) {
            if (!closed) {
                delegate.delete()
                closed = true
            }
        }
    }

    override fun getXSize(): Int {
        return delegate.GetXSize()
    }

    override fun getYSize(): Int {
        return delegate.GetYSize()
    }

    override fun getRasterDataType(): Int {
        return delegate.GetRasterDataType()
    }

    override fun getBlockSize(): IntArray {
        val xSize = IntArray(1)
        val ySize = IntArray(1)
        delegate.GetBlockSize(xSize, ySize)
        return intArrayOf(xSize[0], ySize[0])
    }

    override fun checksum(): Int {
        return delegate.Checksum()
    }

    override fun getColorInterpretation(): Int {
        return delegate.GetColorInterpretation()
    }

    override fun setColorInterpretation(colorInterpretation: Int): Int {
        return delegate.SetColorInterpretation(colorInterpretation)
    }

    override fun getNoDataValue(): Double? {
        val value = arrayOfNulls<Double>(1)
        delegate.GetNoDataValue(value)
        return value[0]
    }

    override fun setNoDataValue(value: Double): Int {
        return delegate.SetNoDataValue(value)
    }

    override fun deleteNoDataValue(): Int {
        return delegate.DeleteNoDataValue()
    }

    override fun getUnitType(): String {
        return delegate.GetUnitType()
    }

    override fun setUnitType(unitType: String): Int {
        return delegate.SetUnitType(unitType)
    }

    override fun getCategoryNames(): List<String> {
        return delegate.GetCategoryNames()
            ?.map { it.toString() }
            .orEmpty()
    }

    override fun setCategoryNames(names: List<String>): Int {
        val categories = Vector<String>()
        names.forEach { categories.add(it) }
        return delegate.SetCategoryNames(categories)
    }

    override fun getOverviewCount(): Int {
        return delegate.GetOverviewCount()
    }

    override fun getOverview(index: Int): Band? {
        return delegate.GetOverview(index)?.let(::JvmBand)
    }

    override fun getSampleOverview(sampleSize: Long): Band? {
        return delegate.GetSampleOverview(sampleSize)?.let(::JvmBand)
    }

    override fun getMaskBand(): Band? {
        return delegate.GetMaskBand()?.let(::JvmBand)
    }

    override fun getMaskFlags(): Int {
        return delegate.GetMaskFlags()
    }

    override fun createMaskBand(flags: Int): Int {
        return delegate.CreateMaskBand(flags)
    }

    override fun isMaskBand(): Boolean {
        return delegate.IsMaskBand()
    }

    override fun flushCache() {
        delegate.FlushCache()
    }

    override fun readRaster(buffer: ByteArray): Int {
        return delegate.ReadRaster(0, 0, getXSize(), getYSize(), buffer)
    }

    override fun readRaster(buffer: ShortArray): Int {
        return delegate.ReadRaster(0, 0, getXSize(), getYSize(), buffer)
    }

    override fun readRaster(buffer: IntArray): Int {
        return delegate.ReadRaster(0, 0, getXSize(), getYSize(), buffer)
    }

    override fun readRaster(buffer: LongArray): Int {
        return delegate.ReadRaster(0, 0, getXSize(), getYSize(), buffer)
    }

    override fun readRaster(buffer: FloatArray): Int {
        return delegate.ReadRaster(0, 0, getXSize(), getYSize(), buffer)
    }

    override fun readRaster(buffer: DoubleArray): Int {
        return delegate.ReadRaster(0, 0, getXSize(), getYSize(), buffer)
    }

    override fun readRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: ByteArray,
    ): Int {
        return delegate.ReadRaster(xOffset, yOffset, xSize, ySize, buffer)
    }

    override fun readRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: ShortArray,
    ): Int {
        return delegate.ReadRaster(xOffset, yOffset, xSize, ySize, buffer)
    }

    override fun readRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: IntArray,
    ): Int {
        return delegate.ReadRaster(xOffset, yOffset, xSize, ySize, buffer)
    }

    override fun readRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: LongArray,
    ): Int {
        return delegate.ReadRaster(xOffset, yOffset, xSize, ySize, buffer)
    }

    override fun readRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: FloatArray,
    ): Int {
        return delegate.ReadRaster(xOffset, yOffset, xSize, ySize, buffer)
    }

    override fun readRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: DoubleArray,
    ): Int {
        return delegate.ReadRaster(xOffset, yOffset, xSize, ySize, buffer)
    }

    override fun writeRaster(buffer: ByteArray): Int {
        return delegate.WriteRaster(0, 0, getXSize(), getYSize(), buffer)
    }

    override fun writeRaster(buffer: ShortArray): Int {
        return delegate.WriteRaster(0, 0, getXSize(), getYSize(), buffer)
    }

    override fun writeRaster(buffer: IntArray): Int {
        return delegate.WriteRaster(0, 0, getXSize(), getYSize(), buffer)
    }

    override fun writeRaster(buffer: LongArray): Int {
        return delegate.WriteRaster(0, 0, getXSize(), getYSize(), buffer)
    }

    override fun writeRaster(buffer: FloatArray): Int {
        return delegate.WriteRaster(0, 0, getXSize(), getYSize(), buffer)
    }

    override fun writeRaster(buffer: DoubleArray): Int {
        return delegate.WriteRaster(0, 0, getXSize(), getYSize(), buffer)
    }

    override fun writeRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: ByteArray,
    ): Int {
        return delegate.WriteRaster(xOffset, yOffset, xSize, ySize, buffer)
    }

    override fun writeRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: ShortArray,
    ): Int {
        return delegate.WriteRaster(xOffset, yOffset, xSize, ySize, buffer)
    }

    override fun writeRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: IntArray,
    ): Int {
        return delegate.WriteRaster(xOffset, yOffset, xSize, ySize, buffer)
    }

    override fun writeRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: LongArray,
    ): Int {
        return delegate.WriteRaster(xOffset, yOffset, xSize, ySize, buffer)
    }

    override fun writeRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: FloatArray,
    ): Int {
        return delegate.WriteRaster(xOffset, yOffset, xSize, ySize, buffer)
    }

    override fun writeRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: DoubleArray,
    ): Int {
        return delegate.WriteRaster(xOffset, yOffset, xSize, ySize, buffer)
    }
}
