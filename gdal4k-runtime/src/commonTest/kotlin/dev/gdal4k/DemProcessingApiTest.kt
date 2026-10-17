package dev.gdal4k

import dev.gdal4k.GdalFile
import dev.gdal4k.GdalFiles
import dev.gdal4k.runtime.*
import kotlinx.coroutines.test.runTest
import kotlin.math.sqrt
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class DemProcessingApiTest {
    private suspend fun <T> source(block: suspend (Dataset) -> T): T =
        requireNotNull(Gdal4k.runtime().getDriver("MEM")).use { driver ->
            requireNotNull(driver.create("", 5, 5, 2, GdalDataType.Float32)).use { ds ->
                assertEquals(0, ds.setGeoTransform(doubleArrayOf(0.0, 1.0, 0.0, 5.0, 0.0, -1.0)))
                Gdal4k.runtime().createSpatialReference("EPSG:3857").use { assertEquals(0, ds.setSpatialRef(it)) }
                requireNotNull(ds.getRasterBand(1)).use { band ->
                    assertEquals(0, band.writeRaster(FloatArray(25) { (it % 5).toFloat() }))
                }
                requireNotNull(ds.getRasterBand(2)).use { assertEquals(0, it.fill(10.0)) }
                block(ds)
            }
        }
    private suspend fun pixels(files: GdalFiles): FloatArray = Gdal4k.runtime().openDataset(files).use { out ->
        FloatArray(out.getRasterXSize() * out.getRasterYSize()).also { assertEquals(0, out.readRaster(it)) }
    }
    @Test fun slopePlaneUnitsAndBandSelection() = runTest(timeout = 120.seconds) { source { ds ->
        val degrees = ds.demProcessing("slope", arguments = listOf("-s", "1"))
        assertEquals(45f, pixels(degrees)[12], 0.0001f)
        assertEquals(100f, pixels(ds.demProcessing("slope", arguments = listOf("-p", "-s", "1")))[12], 0.0001f)
        assertEquals(0f, pixels(ds.demProcessing("slope", arguments = listOf("-b", "2")))[12])
        assertEquals(45f, pixels(ds.demProcessing("slope", arguments = listOf("-alg", "ZevenbergenThorne", "-s", "1")))[12], 0.0001f)
        Gdal4k.runtime().openDataset(degrees).use { out ->
            assertEquals(1, out.getRasterCount()); assertEquals(5, out.getRasterXSize())
            assertContentEquals(ds.getGeoTransform(), out.getGeoTransform())
            assertTrue(out.getProjection().contains("3857"))
            requireNotNull(out.getRasterBand(1)).use { assertEquals(GdalDataType.Float32, it.getRasterDataType()) }
        }
    } }
    @Test fun hillshadeAndAspect() = runTest(timeout = 120.seconds) { source { ds ->
        val shade = ds.demProcessing("hillshade", arguments = listOf("-b", "2", "-alt", "45"))
        assertTrue(pixels(shade)[12] in 180f..182f)
        Gdal4k.runtime().openDataset(shade).use { out -> requireNotNull(out.getRasterBand(1)).use {
            assertEquals(GdalDataType.Byte, it.getRasterDataType()); assertEquals(0.0, it.getNoDataValue())
        } }
        assertEquals(270f, pixels(ds.demProcessing("aspect"))[12], 0.0001f)
        assertEquals(180f, pixels(ds.demProcessing("aspect", arguments = listOf("-trigonometric")))[12], 0.0001f)
        assertEquals(0f, pixels(ds.demProcessing("aspect", arguments = listOf("-b", "2", "-zero_for_flat")))[12])
        assertTrue(pixels(ds.demProcessing("hillshade", arguments = listOf("-multidirectional")))[12] > 0)
    } }
    @Test fun terrainIndices() = runTest(timeout = 120.seconds) { source { ds ->
        assertEquals(sqrt(6.0).toFloat(), pixels(ds.demProcessing("TRI", arguments = listOf("-alg", "Riley")))[12], 0.0001f)
        assertEquals(0.75f, pixels(ds.demProcessing("tri", arguments = listOf("-alg", "Wilson")))[12], 0.0001f)
        assertEquals(0f, pixels(ds.demProcessing("tpi"))[12], 0.0001f)
        assertEquals(2f, pixels(ds.demProcessing("Roughness"))[12], 0.0001f)
    } }
    @Test fun noDataAndEdgeHandling() = runTest(timeout = 120.seconds) { source { ds ->
        val normal = ds.demProcessing("slope", arguments = listOf("-s", "1"))
        assertEquals(-9999f, pixels(normal)[0]); assertEquals(45f, pixels(normal)[12], 0.0001f)
        val edges = pixels(ds.demProcessing("slope", arguments = listOf("-compute_edges", "-s", "1")))
        assertTrue(edges.all { it >= 0f && it <= 90f })
        requireNotNull(ds.getRasterBand(1)).use { band ->
            assertEquals(0, band.setNoDataValue(-9999.0))
            val values = FloatArray(25) { (it % 5).toFloat() }; values[12] = -9999f
            assertEquals(0, band.writeRaster(values))
        }
        assertEquals(-9999f, pixels(ds.demProcessing("slope"))[12])
        assertEquals(-9999f, pixels(ds.demProcessing("slope", arguments = listOf("-compute_edges")))[12])
    } }
    @Test fun independentResultsAndDirectOutput() = runTest(timeout = 120.seconds) {
        val runtime = Gdal4k.runtime(); val path = datasetOutputPath("dem-api.tif")
        val cogPath = datasetOutputPath("dem-api-cog.tif")
        requireNotNull(runtime.getDriver("GTiff")).use { driver -> try {
            val result = source { ds ->
                ds.demProcessingTo(path, "hillshade", arguments = listOf("-b", "2", "-co", "COMPRESS=JXL", "-co", "JXL_LOSSLESS=YES"))
                ds.demProcessingTo(cogPath, "slope", driver = "COG", arguments = listOf("-s", "1"))
                ds.demProcessing("slope", name = "independent.tif", arguments = listOf("-s", "1"))
            }
            assertEquals("independent.tif", result.main); assertEquals(45f, pixels(result)[12], 0.0001f)
            runtime.openDataset(path).use { ds ->
                val data = FloatArray(25); assertEquals(0, ds.readRaster(data)); assertTrue(data[12] in 180f..182f)
                assertTrue(ds.info().contains("JXL"))
            }
            runtime.openDataset(cogPath).use { ds ->
                val data = FloatArray(25); assertEquals(0, ds.readRaster(data)); assertEquals(45f, data[12], 0.0001f)
                assertTrue(ds.info().contains("COG"))
            }
        } finally { driver.delete(path); driver.delete(cogPath) } }
    }
    @Test fun validationFailuresPreserveSource() = runTest(timeout = 120.seconds) {
        source { ds ->
            for (mode in listOf("", "missing", "color-relief", "slope\u0000")) assertFails { ds.demProcessing(mode) }
            for (driver in listOf("", "MEM", "vrt", "missing_driver")) assertFails { ds.demProcessing("slope", driver) }
            for (args in listOf(listOf("-b", "0"), listOf("-b", "3"), listOf("-not-an-option"), listOf("-az", "5"), listOf("-co", "X=\u0000")))
                assertFails { ds.demProcessing("slope", arguments = args) }
            assertFails { ds.demProcessingTo("", "slope") }
            assertFails { ds.demProcessingTo("bad\u0000path", "slope") }
            assertFails { ds.demProcessing("slope", name = "../bad.tif") }
            assertEquals(45f, pixels(ds.demProcessing("slope", arguments = listOf("-s", "1")))[12], 0.0001f)
            ds.close(); assertFails { ds.demProcessing("slope") }
        }
        val json = """{"type":"FeatureCollection","features":[]}"""
        Gdal4k.runtime().openDataset(GdalFiles(GdalFile("empty.geojson", json.encodeToByteArray())), DatasetOpenOptions(kind = DatasetKind.Vector)).use { ds ->
            assertFails { ds.demProcessing("slope") }
        }
    }
}
