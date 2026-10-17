package dev.gdal4k

import dev.gdal4k.runtime.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class BundledResourcesTest {
    @Test
    fun cogUsesBundledTileMatrixDefinition() = runTest(timeout = 120.seconds) {
        try {
            val runtime = Gdal4k.runtime()
            requireNotNull(runtime.getDriver("MEM")).use { driver ->
                requireNotNull(driver.create("", 16, 16, 1, GdalDataType.Byte)).use { source ->
                    runtime.createSpatialReference("EPSG:2193").use { crs ->
                        assertEquals(0, source.setProjection(crs.exportToWkt()))
                    }
                    assertEquals(0, source.setGeoTransform(doubleArrayOf(1700000.0, 10.0, 0.0, 5400000.0, 0.0, -10.0)))
                    requireNotNull(source.getRasterBand(1)).use { band ->
                        assertEquals(0, band.writeRaster(ByteArray(256) { 42 }))
                    }
                    // This non-built-in scheme requires stat, directory listing,
                    // seek and read of the bundled tms_NZTM2000.json resource.
                    val output = source.translate("COG", "nztm.tif", listOf("-co", "TILING_SCHEME=NZTM2000", "-co", "ZOOM_LEVEL=12"))
                    runtime.openDataset(output).use { dataset ->
                        assertTrue(dataset.getRasterXSize() > 0)
                        assertTrue(dataset.getRasterYSize() > 0)
                        assertTrue(dataset.getProjection().contains("2193"))
                    }
                }
            }
        } finally { Gdal4k.close() }
    }
}
