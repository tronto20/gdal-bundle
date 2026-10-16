package dev.gdal4k

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.math.abs

internal suspend fun integrationOptions(): GdalOptions = GdalOptions()

class GdalIntegrationTest {
    @Test
    fun publicApi() = runTest(timeout = 120.seconds) {
        val options = integrationOptions()
        val pixels = doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0, 255.0)
        val fixture = GdalFile("영상-🛰.tif", Base64.decode("SUkqAAgAAAAOAAABBAABAAAAAwAAAAEBBAABAAAAAgAAAAIBAwABAAAACAAAAAMBAwABAAAAAQAAAAYBAwABAAAAAQAAABEBBAABAAAAHgEAABUBAwABAAAAAQAAABYBBAABAAAAAgAAABcBBAABAAAABgAAAFMBAwABAAAAAQAAAA6DDAADAAAAtgAAAIKEDAAGAAAAzgAAAK+HAwAQAAAA/gAAAIGkAgACAAAAMAAAAAAAAAAAAAAAAADgPwAAAAAAAOA/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABZQAAAAAAAAElAAAAAAAAAAAABAAEAAAADAAAEAAABAAIAAQQAAAEAAQAACAAAAQDmEAABAgME/w=="))
        val vector = GdalFile("points.geojson", """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[10,45]},"properties":{}}]}
    """.encodeToByteArray())
        var stale: GdalDataset? = null
        val session = Gdal.create(options)
        session.use { gdal ->
            val drivers = gdal.drivers()
            check(drivers.any { it.name == "GTiff" && it.raster })
            check(drivers.any { it.name == "JPEGXL" && it.raster })
            check(drivers.any { it.name == "GeoJSON" && it.vector })
            gdal.open(fixture).use { dataset ->
                check(dataset.info.width == 3 && dataset.info.height == 2 && dataset.info.bandCount == 1)
                val info = dataset.info()
                check(info["bands"]!!.jsonArray[0].jsonObject["noDataValue"]!!.jsonPrimitive.double == 0.0)
                check(info["geoTransform"]!!.jsonArray.map { it.jsonPrimitive.double } == listOf(100.0, 0.5, 0.0, 50.0, 0.0, -0.5))
                check(dataset.readRaster().contentEquals(pixels))
                // Independent engines and identical file names must not share handles or file contents.
                Gdal.create(options).use { second ->
                    second.open(fixture).use { other -> check(other.readRaster().contentEquals(pixels)) }
                }
                check(dataset.readRaster(window = RasterWindow(1, 0, 2, 2)).contentEquals(doubleArrayOf(1.0, 2.0, 4.0, 255.0)))
                for (driver in listOf("GTiff", "COG")) {
                    val output = dataset.translate(driver)
                    gdal.open(output).use { reopened ->
                        check(reopened.readRaster().contentEquals(pixels))
                        if (driver == "COG") check(reopened.info()["metadata"]!!.jsonObject["IMAGE_STRUCTURE"]!!.jsonObject["LAYOUT"]!!.jsonPrimitive.content == "COG")
                    }
                }
                for (driver in listOf("JPEGXL", "GTiff", "COG")) {
                    val arguments = if (driver == "JPEGXL") listOf("-co", "LOSSLESS=YES") else listOf("-co", "COMPRESS=JXL", "-co", "JXL_LOSSLESS=YES")
                    val output = dataset.translate(driver, if (driver == "JPEGXL") "output.jxl" else "output.tif", arguments)
                    gdal.open(output).use { reopened ->
                        check(reopened.readRaster().contentEquals(pixels)) { "$driver JXL pixels differ" }
                        if (driver != "JPEGXL") check(reopened.info().toString().contains("JXL"))
                    }
                }
                val warped = dataset.warp("EPSG:3857", arguments = listOf("-ts", "3", "2", "-r", "near"))
                gdal.open(warped).use { reopened ->
                    check(reopened.info.width == 3 && reopened.info.height == 2)
                    check(reopened.info()["coordinateSystem"]!!.jsonObject["wkt"]!!.jsonPrimitive.content.contains("3857"))
                }
                // Same filename, another live dataset. Inputs must not overwrite one another.
                gdal.open(fixture).use { other -> check(other.readRaster().contentEquals(pixels)) }
                expectFailure { dataset.translate("GDAL4K_MISSING_DRIVER") }
                expectFailure { dataset.info(listOf("--gdal4k-invalid-option")) }
                check(dataset.readRaster().contentEquals(pixels)) // error state must be reset
                stale = dataset
            }
            expectFailure { stale!!.info() }
            val transformed = gdal.transform("EPSG:4326", "EPSG:3857", listOf(GdalPoint(10.0, 45.0))).single()
            check(abs(transformed.x - 1113194.9079327357) < 0.01 && abs(transformed.y - 5621521.486192066) < 0.01)
            val roundtrip = gdal.transform("EPSG:3857", "EPSG:4326", listOf(transformed)).single()
            check(abs(roundtrip.x - 10.0) < 1e-8 && abs(roundtrip.y - 45.0) < 1e-8)
            expectFailure { gdal.transform("EPSG:NOT_A_CRS", "EPSG:3857", listOf(GdalPoint(0.0, 0.0))) }
            expectFailure { gdal.transform("EPSG:4326", "EPSG:3857", listOf(GdalPoint(0.0, 100.0))) }
            expectFailure { gdal.open(GdalFile("bad.tif", byteArrayOf(1, 2, 3))) }
            gdal.open(vector).use { ds ->
                check(ds.info.layerCount == 1)
                check(ds.info()["layers"]!!.jsonArray.single().jsonObject["featureCount"]!!.jsonPrimitive.int == 1)
                val shp = ds.translateVector("ESRI Shapefile", "shape.shp")
                check(shp.files.any { it.name.endsWith(".dbf") } && shp.files.any { it.name.endsWith(".shx") })
                gdal.open(shp).use { reopened ->
                    val json = reopened.translateVector().mainFile().bytes.decodeToString()
                    check(Json.parseToJsonElement(json).jsonObject["features"]!!.jsonArray.size == 1)
                }
            }
            // Closing the engine must release a dataset that the consumer forgot to close.
            stale = gdal.open(fixture)
        }
        session.close() // idempotent
        stale!!.close() // idempotent after parent close
        expectFailure { session.drivers() }
        expectFailure { stale!!.readRaster() }
        println("GDAL4K_INTEGRATION_TESTS_OK")
    }
}

private suspend fun expectFailure(block: suspend () -> Unit) {
    var failed = false
    try { block() } catch (_: Exception) { failed = true }
    check(failed) { "Expected failure was accepted as success" }
}
