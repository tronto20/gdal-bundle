@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package dev.gdal4k
import dev.gdal4k.runtime.*
import kotlinx.coroutines.test.runTest
import org.w3c.files.File
import kotlin.test.*
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.*
@JsFun("(name, data) => new File([Uint8Array.from(atob(data), c => c.charCodeAt(0))], name)")
private external fun selectedFile(name: String, data: String): File
@JsFun("() => new File([Uint8Array.from(atob('SUkqAAgAAAAOAAABBAABAAAAAwAAAAEBBAABAAAAAgAAAAIBAwABAAAACAAAAAMBAwABAAAAAQAAAAYBAwABAAAAAQAAABEBBAABAAAAHgEAABUBAwABAAAAAQAAABYBBAABAAAAAgAAABcBBAABAAAABgAAAFMBAwABAAAAAQAAAA6DDAADAAAAtgAAAIKEDAAGAAAAzgAAAK+HAwAQAAAA/gAAAIGkAgACAAAAMAAAAAAAAAAAAAAAAADgPwAAAAAAAOA/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABZQAAAAAAAAElAAAAAAAAAAAABAAEAAAADAAAEAAABAAIAAQQAAAEAAQAACAAAAQDmEAABAgME/w=='), c => c.charCodeAt(0))], 'selected.tif')")
private external fun fixtureFile(): File
@JsFun("() => typeof window !== 'undefined'")
private external fun inBrowser(): Boolean
class BrowserFileTest {
    @Test fun shapefileSelectedFilesUseSidecars() = runTest {
        if (!inBrowser()) return@runTest
        try {
            val runtime = Gdal4k.runtime()
            val shape = runtime.openDataset("points.geojson", """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[10,45]},"properties":{"name":"road-one"}}]}""".encodeToByteArray())
                .use { it.translateVector("ESRI Shapefile", "roads.shp", listOf("-t_srs", "EPSG:3857")) }
            val files = shape.files.map { selectedFile(it.name, Base64.encode(it.bytes)) }
            runtime.openDataset("roads.shp", files).use {
                val json = it.translateVector(arguments = listOf("-t_srs", "EPSG:4326")).mainFile().bytes.decodeToString()
                val feature = Json.parseToJsonElement(json).jsonObject.getValue("features").jsonArray.single().jsonObject
                assertEquals("road-one", feature.getValue("properties").jsonObject.getValue("name").jsonPrimitive.content)
                val coordinates = feature.getValue("geometry").jsonObject.getValue("coordinates").jsonArray
                assertEquals(10.0, coordinates[0].jsonPrimitive.double, 1e-7)
                assertEquals(45.0, coordinates[1].jsonPrimitive.double, 1e-7)
            }
            assertFailsWith<IllegalArgumentException> { runtime.openDataset("roads.shp", files + files.first()) }
        } finally { Gdal4k.close() }
    }
    @Test fun selectedFileUsesOriginalDatasetApi() = runTest {
        if (!inBrowser()) return@runTest
        try {
            val runtime = Gdal4k.runtime()
            runtime.openDataset(fixtureFile()).use { dataset ->
                assertEquals(3, dataset.getRasterXSize())
                assertEquals(2, dataset.getRasterYSize())
                val pixels = ByteArray(6)
                requireNotNull(dataset.getRasterBand(1)).use { band -> assertEquals(0, band.readRaster(pixels)) }
                assertContentEquals(byteArrayOf(0,1,2,3,4,-1), pixels)
            }
            assertFailsWith<IllegalArgumentException> {
                runtime.openDataset(fixtureFile(), DatasetOpenOptions(accessMode = DatasetAccessMode.Update))
            }
        } finally { Gdal4k.close() }
    }
}
