package dev.gdal4k

import dev.gdal4k.GdalFiles
import dev.gdal4k.runtime.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class PolygonizeApiTest {
    private suspend fun raster(values: IntArray = intArrayOf(1, 0, 0, 1), width: Int = 2): Dataset {
        val r = Gdal4k.runtime()
        return requireNotNull(r.getDriver("MEM")).use { driver ->
            requireNotNull(driver.create("", width, values.size / width, 1, GdalDataType.Int32)).also { ds ->
                assertEquals(0, ds.writeRaster(values))
                assertEquals(0, ds.setGeoTransform(doubleArrayOf(100.0, 2.0, 0.0, 50.0, 0.0, -3.0)))
                r.createSpatialReference("EPSG:3857").use { assertEquals(0, ds.setSpatialRef(it)) }
            }
        }
    }
    private suspend fun features(files: GdalFiles, field: String = "value"): List<Pair<String, Double>> =
        Gdal4k.runtime().openDataset(files).use { ds ->
            requireNotNull(ds.getLayer(0)).use { layer ->
                val result = mutableListOf<Pair<String, Double>>()
                while (true) {
                    val feature = layer.getNextFeature() ?: break
                    feature.use { f ->
                        requireNotNull(f.getGeometryRef()).use { geometry ->
                            result += f.getFieldAsString(field) to geometry.getArea()
                        }
                    }
                }
                result
            }
        }

    @Test fun connectivityNoDataGeoreferencingAndIndependentOutput() = runTest(timeout = 120.seconds) {
        val r = Gdal4k.runtime()
        val files = raster().use { ds ->
            requireNotNull(ds.getRasterBand(1)).use { assertEquals(0, it.setNoDataValue(0.0)) }
            val four = ds.polygonize(driver = "GeoJSON", name = "four.geojson")
            val eight = ds.polygonize("-8", driver = "GeoJSON", name = "eight.geojson")
            assertEquals(listOf("1", "1"), features(four).map { it.first })
            assertEquals(12.0, features(four).sumOf { it.second }, 0.0001)
            assertEquals(listOf("1"), features(eight).map { it.first })
            assertEquals(12.0, features(eight).sumOf { it.second }, 0.0001)
            val all = ds.polygonize("-nomask", driver = "GeoJSON", name = "all.geojson")
            assertEquals(listOf("0", "0", "1", "1"), features(all).map { it.first }.sorted())
            four
        }
        r.openDataset(files).use { ds ->
            assertTrue(ds.info().contains("3857"))
            requireNotNull(ds.getLayer(0)).use { layer ->
                assertEquals(2, layer.getFeatureCount(true))
                assertContentEquals(doubleArrayOf(100.0, 104.0, 44.0, 50.0), layer.getExtent(true))
            }
        }
    }

    @Test fun explicitMaskAndBandSelection() = runTest(timeout = 120.seconds) {
        val r = Gdal4k.runtime()
        raster(intArrayOf(7, 7, 7, 7)).use { ds ->
            requireNotNull(ds.getRasterBand(1)).use { assertEquals(0, it.setNoDataValue(7.0)) }
            assertTrue(features(ds.polygonize()).isEmpty())
            raster(intArrayOf(1, 0, 0, 1)).use { maskDataset ->
                requireNotNull(maskDataset.getRasterBand(1)).use { mask ->
                    assertEquals(listOf("7", "7"), features(ds.polygonize(mask = mask)).map { it.first })
                    assertFails { ds.polygonize("-nomask", mask = mask) }
                }
            }
            assertEquals(0, ds.addBand(GdalDataType.Int32))
            requireNotNull(ds.getRasterBand(2)).use { assertEquals(0, it.fill(9.0)) }
            assertEquals(listOf("9"), features(ds.polygonize(band = 2)).map { it.first })
            assertEquals(2, ds.getRasterCount())
        }
    }

    @Test fun realValuesDoNotTruncateAndInt64IsExact() = runTest(timeout = 120.seconds) {
        val r = Gdal4k.runtime()
        requireNotNull(r.getDriver("MEM")).use { driver ->
            requireNotNull(driver.create("", 2, 1, 1, GdalDataType.Float64)).use { ds ->
                assertEquals(0, ds.writeRaster(doubleArrayOf(1.25, 1.75)))
                r.openDataset(ds.polygonize()).use { output ->
                    requireNotNull(output.getLayer(0)).use { layer ->
                        val values = mutableListOf<Double>()
                        while (true) {
                            val f = layer.getNextFeature() ?: break
                            f.use { assertEquals(2, it.getFieldType("value")); values += it.getFieldAsDouble("value") }
                        }
                        assertEquals(listOf(1.25, 1.75), values.sorted())
                    }
                }
            }
            requireNotNull(driver.create("", 2, 1, 1, GdalDataType.Int64)).use { ds ->
                val value = 9_007_199_254_740_993L
                assertEquals(0, ds.writeRaster(longArrayOf(value, value)))
                r.openDataset(ds.polygonize()).use { output ->
                    requireNotNull(output.getLayer(0)).use { layer ->
                        assertEquals(1, layer.getFeatureCount(true))
                        requireNotNull(layer.getNextFeature()).use { assertEquals(value, it.getFieldAsInteger64("value")) }
                    }
                }
            }
        }
    }

    @Test fun directGeoJsonAndGeoPackageWithCreationOptions() = runTest(timeout = 120.seconds) {
        val r = Gdal4k.runtime()
        raster().use { ds ->
            for ((format, extension) in listOf("GPKG" to "gpkg", "GeoJSON" to "geojson")) {
                val path = datasetOutputPath("polygonize-direct.$extension")
                requireNotNull(r.getDriver(format)).use { driver ->
                    try {
                        if (format == "GPKG") {
                            ds.polygonizeTo(path, "-lco", "FID=poly_id", driver = format, layerName = "classes", fieldName = "class_id")
                        } else {
                            ds.polygonizeTo(path, driver = format, layerName = "classes", fieldName = "class_id", arguments = emptyList())
                        }
                        r.openDataset(path).use { output ->
                            requireNotNull(output.getLayer(0)).use { layer ->
                                assertEquals(4, layer.getFeatureCount(true))
                                if (format == "GPKG") {
                                    assertEquals("classes", layer.getName())
                                    assertEquals("poly_id", layer.getFIDColumn())
                                }
                                requireNotNull(layer.getNextFeature()).use { assertTrue(it.getFieldIndex("class_id") >= 0) }
                            }
                        }
                        assertFails { ds.polygonizeTo(path, driver = format) }
                        r.openDataset(path).use { output -> assertEquals(4, requireNotNull(output.getLayer(0)).getFeatureCount(true)) }
                    } finally { driver.delete(path) }
                }
            }
        }
    }

    @Test fun exactInt64WithHolesRotationAndDiagonalConnectivity() = runTest(timeout = 120.seconds) {
        val r = Gdal4k.runtime()
        requireNotNull(r.getDriver("MEM")).use { driver ->
            requireNotNull(driver.create("", 3, 3, 1, GdalDataType.Int64)).use { ds ->
                val large = 9_007_199_254_740_993L
                assertEquals(0, ds.setGeoTransform(doubleArrayOf(100.0, 2.0, 0.5, 50.0, 0.25, -3.0)))
                val values = LongArray(9) { large }.also { it[4] = Long.MIN_VALUE }
                assertEquals(0, ds.writeRaster(values))
                val read = LongArray(9); assertEquals(0, ds.readRaster(read)); assertContentEquals(values, read)
                val expected = listOf(Long.MIN_VALUE.toString(), large.toString()).sorted()
                val exactFiles = ds.polygonize(driver = "GeoJSON", name = "exact.geojson")
                val json = exactFiles.mainFile().bytes.decodeToString()
                // GeoJSON readers can infer Real when the range includes Long.MIN_VALUE.
                // Verify the raw integer text, and use GPKG for exact Int64 round trips.
                assertTrue(json.contains(large.toString()))
                assertTrue(json.contains(Long.MIN_VALUE.toString()))
                assertEquals(expected, features(ds.polygonize()).map { it.first }.sorted())
                assertEquals(0, ds.writeRaster(LongArray(9) { if (it / 3 == it % 3) large else Long.MIN_VALUE }))
                assertEquals(expected, features(ds.polygonize("-8")).map { it.first }.sorted())
            }
        }
    }

    @Test fun virtualSourceRetainsClosedInputs() = runTest(timeout = 120.seconds) {
        val r = Gdal4k.runtime()
        val vrt = raster(intArrayOf(3, 3, 3, 3)).use { r.buildVRT(listOf(it)) }
        vrt.use { assertEquals(listOf("3"), features(it.polygonize()).map { f -> f.first }) }
    }

    @Test fun invalidOptionsMasksFormatsAndClosedHandles() = runTest(timeout = 120.seconds) {
        val r = Gdal4k.runtime()
        raster().use { ds ->
            for (band in listOf(0, 2)) assertFails { ds.polygonize(band = band) }
            for (format in listOf("MEM", "Memory", "VRT", "GTiff", "missing")) {
                assertFails { ds.polygonize(driver = format) }
                assertFails { ds.polygonizeTo(datasetOutputPath("invalid-polygonize"), driver = format) }
            }
            for (args in listOf(listOf("-bad"), listOf("-lco"), listOf("-dsco", "bad"), listOf("-8", "-8"), listOf("-8\u0000"))) {
                assertFails { ds.polygonize(arguments = args) }
            }
            assertFails { ds.polygonize(layerName = "") }
            assertFails { ds.polygonize(fieldName = "bad\u0000") }
            assertFails { ds.polygonize(name = "../bad.gpkg") }
            assertFails { ds.polygonizeTo("") }
            raster(intArrayOf(1), width = 1).use { maskDs ->
                requireNotNull(maskDs.getRasterBand(1)).use { assertFails { ds.polygonize(mask = it) } }
            }
            val closed = requireNotNull(ds.getRasterBand(1)); closed.close()
            assertFails { ds.polygonize(mask = closed) }
            assertEquals(4, features(ds.polygonize()).size)
            ds.close(); assertFails { ds.polygonize() }
        }
        requireNotNull(r.getDriver("MEM")).use { driver ->
            for (type in listOf(GdalDataType.UInt64, GdalDataType.CFloat32)) {
                requireNotNull(driver.create("", 1, 1, 1, type)).use { assertFails { it.polygonize() } }
            }
        }
    }
}
