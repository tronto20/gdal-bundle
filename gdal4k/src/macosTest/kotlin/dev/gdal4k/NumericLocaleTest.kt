@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.gdal4k

import dev.gdal4k.runtime.*
import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import platform.posix.LC_NUMERIC
import platform.posix.setlocale
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class NumericLocaleTest {
    @Test fun numericValuesDoNotUseProcessLocale() = runTest(timeout = 120.seconds) {
        val previous = requireNotNull(setlocale(LC_NUMERIC, null)).toKString()
        try {
            assertNotNull(setlocale(LC_NUMERIC, "fr_FR.UTF-8"))
            val runtime = Gdal4k.runtime()
            requireNotNull(runtime.getDriver("MEM")).use { memory ->
                requireNotNull(memory.create("", 2, 2, 1, GdalDataType.Float64)).use { dataset ->
                    val band = requireNotNull(dataset.getRasterBand(1))
                    val expected = doubleArrayOf(123.5, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
                    assertEquals(0, band.writeRaster(expected))
                    val actual = DoubleArray(4)
                    assertEquals(0, band.readRaster(actual))
                    assertEquals(123.5, actual[0])
                    assertTrue(actual[1].isNaN())
                    assertEquals(Double.POSITIVE_INFINITY, actual[2])
                    assertEquals(Double.NEGATIVE_INFINITY, actual[3])
                    val transform = doubleArrayOf(10.5, 0.25, 0.0, 50.5, 0.0, -0.25)
                    assertEquals(0, dataset.setGeoTransform(transform))
                    assertContentEquals(transform, dataset.getGeoTransform())
                }
            }
            runtime.createSpatialReference("EPSG:3857").use { reference ->
                assertEquals(0, reference.setLinearUnits("custom", 1.25))
                assertEquals(1.25, reference.getLinearUnits())
            }
        } finally {
            setlocale(LC_NUMERIC, previous)
            Gdal4k.close()
        }
    }
}
