package dev.gdal4k

import dev.gdal4k.runtime.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class RasterizeApiTest {
    private val grid = listOf("-te", "0", "0", "2", "2", "-ts", "2", "2")
    private fun polygon(left: Double, right: Double, value: Int, bottom: Double = 0.0, top: Double = 2.0) =
        """{"type":"Feature","properties":{"value":$value},"geometry":{"type":"Polygon","coordinates":[[[$left,$bottom],[$right,$bottom],[$right,$top],[$left,$top],[$left,$bottom]]]}}"""
    private suspend fun source(vararg features: String): Dataset = Gdal4k.runtime().openDataset(
        GdalFiles(GdalFile("zones.geojson", """{"type":"FeatureCollection","name":"zones","features":[${features.joinToString(",")}] }""".encodeToByteArray())),
        DatasetOpenOptions(kind = DatasetKind.Vector),
    )
    private suspend fun pixels(files: GdalFiles): IntArray = Gdal4k.runtime().openDataset(files).use { ds ->
        IntArray(ds.getRasterXSize() * ds.getRasterYSize() * ds.getRasterCount()).also { assertEquals(0, ds.readRaster(it)) }
    }
    @Test fun constantBurnMultibandAndNoData() = runTest(timeout = 120.seconds) {
        source(polygon(0.0, 1.0, 5)).use { ds ->
            val files = ds.rasterize(arguments = grid + listOf("-burn", "3", "-burn", "7", "-ot", "Int16", "-init", "-1", "-a_nodata", "-99"))
            assertContentEquals(intArrayOf(3, -1, 3, -1, 7, -1, 7, -1), pixels(files))
            Gdal4k.runtime().openDataset(files).use { out ->
                assertEquals(2, out.getRasterCount())
                requireNotNull(out.getRasterBand(1)).use { assertEquals(-99.0, it.getNoDataValue()) }
                assertContentEquals(doubleArrayOf(0.0, 1.0, 0.0, 2.0, 0.0, -1.0), out.getGeoTransform())
            }
            assertEquals(1, ds.getLayerCount())
        }
    }
    @Test fun attributesWhereAndSqlSelection() = runTest(timeout = 120.seconds) {
        source(polygon(0.0, 1.0, 5), polygon(1.0, 2.0, 9)).use { ds ->
            assertContentEquals(intArrayOf(5, 9, 5, 9), pixels(ds.rasterize(arguments = grid + listOf("-a", "value", "-l", "zones"))))
            assertContentEquals(intArrayOf(0, 9, 0, 9), pixels(ds.rasterize(arguments = grid + listOf("-a", "value", "-where", "value = 9"))))
            assertContentEquals(intArrayOf(4, 0, 4, 0), pixels(ds.rasterize(arguments = grid + listOf("-burn", "4", "-sql", "SELECT * FROM zones WHERE value = 5"))))
        }
    }
    @Test fun additiveInverseAndAllTouched() = runTest(timeout = 120.seconds) {
        source(polygon(0.0, 2.0, 2), polygon(0.0, 2.0, 3)).use { ds ->
            assertContentEquals(intArrayOf(5, 5, 5, 5), pixels(ds.rasterize(arguments = grid + listOf("-a", "value", "-add"))))
        }
        source(polygon(0.0, 1.0, 1)).use { ds ->
            assertContentEquals(intArrayOf(0, 6, 0, 6), pixels(ds.rasterize(arguments = grid + listOf("-burn", "6", "-i"))))
        }
        source(polygon(0.1, 0.4, 1, 1.6, 1.9)).use { ds ->
            assertContentEquals(intArrayOf(0, 0, 0, 0), pixels(ds.rasterize(arguments = grid + listOf("-burn", "8"))))
            assertContentEquals(intArrayOf(8, 0, 0, 0), pixels(ds.rasterize(arguments = grid + listOf("-burn", "8", "-at"))))
        }
    }
    @Test fun directOutputAndIndependentMemoryResult() = runTest(timeout = 120.seconds) {
        val r = Gdal4k.runtime(); val path = datasetOutputPath("rasterize-api.tif")
        requireNotNull(r.getDriver("GTiff")).use { driver ->
            try {
                val files = source(polygon(0.0, 1.0, 13)).use { ds ->
                    val args = grid + listOf("-a", "value", "-ot", "Byte", "-co", "COMPRESS=JXL", "-co", "JXL_LOSSLESS=YES")
                    ds.rasterizeTo(path, arguments = args)
                    ds.rasterize(name = "burned.tif", arguments = args)
                }
                assertContentEquals(intArrayOf(13, 0, 13, 0), pixels(files))
                r.openDataset(path).use { ds ->
                    val data = IntArray(4); assertEquals(0, ds.readRaster(data))
                    assertContentEquals(intArrayOf(13, 0, 13, 0), data)
                    assertTrue(ds.info().contains("JXL"))
                }
            } finally { driver.delete(path) }
        }
    }
    @Test fun invalidOptionsAndClosedSources() = runTest(timeout = 120.seconds) {
        source(polygon(0.0, 1.0, 5)).use { ds ->
            assertFails { ds.rasterize(name = "../bad.tif", arguments = grid) }
            assertFails { ds.rasterizeTo("", arguments = grid) }
            assertFails { ds.rasterize(arguments = listOf("-not-a-real-option")) }
            assertFails { ds.rasterize(arguments = grid + listOf("-a", "missing_field")) }
            assertFails { ds.rasterize(arguments = grid + listOf("-burn", "1\u0000")) }
            for (driver in listOf("MEM", "vrt", "missing_driver")) assertFails { ds.rasterize(driver, arguments = grid + listOf("-burn", "1")) }
            assertContentEquals(intArrayOf(2, 0, 2, 0), pixels(ds.rasterize(arguments = grid + listOf("-burn", "2"))))
            ds.close(); assertFails { ds.rasterize(arguments = grid) }
        }
        requireNotNull(Gdal4k.runtime().getDriver("MEM")).use { driver ->
            requireNotNull(driver.create("", 1, 1, 1, GdalDataType.Byte)).use { raster ->
                assertFails { raster.rasterize(arguments = grid + listOf("-burn", "1")) }
                assertEquals(1, raster.getRasterCount())
            }
        }
    }
}
