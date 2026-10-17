package dev.gdal4k

import dev.gdal4k.runtime.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class OgrVectorApiTest {
    private val square = "POLYGON ((0 0,2 0,2 2,0 2,0 0))"
    private suspend fun points(): Dataset = Gdal4k.runtime().openDataset("points.geojson", """{"type":"FeatureCollection","features":[
        {"type":"Feature","id":10,"properties":{"name":"a"},"geometry":{"type":"Point","coordinates":[0,0]}},
        {"type":"Feature","id":20,"properties":{"name":"b"},"geometry":{"type":"Point","coordinates":[10,10]}},
        {"type":"Feature","id":30,"properties":{"name":"c"},"geometry":{"type":"Point","coordinates":[20,20]}}
    ]}""".encodeToByteArray())

    @Test fun geometryFactoriesAndBinaryRoundTrips() = runTest(timeout = 120.seconds) {
        val r = Gdal4k.runtime()
        r.createGeometryFromWkt(square).use { polygon ->
            assertEquals(4.0, polygon.getArea(), 1e-9)
            r.createGeometryFromWkb(polygon.exportToWkb()).use { assertTrue(polygon.equal(it)) }
            r.createGeometryFromGeoJson(polygon.exportToJson()).use { assertTrue(polygon.equal(it)) }
        }
        r.createGeometryFromWkt("POINT ZM (12.5 -4 7 9)").use { point ->
            assertTrue(point.is3D()); assertTrue(point.isMeasured())
            r.createGeometryFromWkb(point.exportToIsoWkb()).use {
                assertTrue(it.is3D()); assertTrue(it.isMeasured())
                assertContentEquals(doubleArrayOf(12.5, -4.0, 7.0), it.getPoint(0))
                assertEquals(point.exportToIsoWkt(), it.exportToIsoWkt())
            }
        }
        r.createGeometryFromWkt("GEOMETRYCOLLECTION (POINT (1 2),LINESTRING (0 0,1 1))").use {
            assertEquals(2, it.getGeometryCount())
        }
        r.createGeometryFromWkt("POLYGON EMPTY").use { assertTrue(it.isEmpty()) }
    }

    @Test fun invalidAndTruncatedInputsFailWithoutDamagingRuntime() = runTest(timeout = 120.seconds) {
        val r = Gdal4k.runtime()
        for (text in listOf("", "not WKT", "POINT (1)", "POINT (1 2) junk", "POINT (1 2)\u0000ignored")) {
            assertFails { r.createGeometryFromWkt(text) }
        }
        for (json in listOf("", "{}", "{", "{\"type\":\"missing\"}", "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[1,2]}}", "{\"type\":\"Point\",\"coordinates\":[1,2]}\u0000")) {
            assertFails { r.createGeometryFromGeoJson(json) }
        }
        val bytes = r.createGeometryFromWkt("POINT (3 4)").use { it.exportToWkb() }
        for (n in listOf(0, 1, 5, bytes.size - 1)) assertFails { r.createGeometryFromWkb(bytes.copyOf(n)) }
        assertFails { r.createGeometryFromWkb(byteArrayOf(9, 0, 0, 0, 0)) }
        r.createGeometryFromWkt("POINT (3 4)").use { assertContentEquals(doubleArrayOf(3.0,4.0,0.0),it.getPoint(0)) }
    }

    @Test fun spatialReferenceAssignmentAndIndependentCopies() = runTest(timeout = 120.seconds) {
        val r = Gdal4k.runtime()
        val geometry = r.createSpatialReference("EPSG:3857").use { srs -> r.createGeometryFromWkt(square, srs) }
        val copy = requireNotNull(geometry.getSpatialReference())
        geometry.use {
            assertEquals(4.0, it.getArea())
            it.assignSpatialReference(null); assertNull(it.getSpatialReference())
            it.assignSpatialReference(copy)
            requireNotNull(it.getSpatialReference()).use { srs -> assertTrue(srs.isSame(copy)) }
            val wkb = it.exportToWkb()
            r.createGeometryFromWkb(wkb, copy).use { other ->
                requireNotNull(other.getSpatialReference()).use { srs -> assertTrue(srs.isSame(copy)) }
            }
            r.createGeometryFromGeoJson(it.exportToJson(), copy).use { other ->
                requireNotNull(other.getSpatialReference()).use { srs -> assertTrue(srs.isSame(copy)) }
            }
        }
        copy.use { assertTrue(it.exportToWkt().contains("3857")) }
        val closed = r.createSpatialReference("EPSG:3857"); closed.close()
        assertFails { r.createGeometryFromWkt(square, closed) }
    }

    @Test fun overlaysArePlanarAndResultsOutliveInputs() = runTest(timeout = 120.seconds) {
        val r = Gdal4k.runtime()
        val outputs = r.createGeometryFromWkt(square).use { left ->
            r.createGeometryFromWkt("POLYGON ((1 1,3 1,3 3,1 3,1 1))").use { right ->
                val result = listOf(requireNotNull(left.intersection(right)),requireNotNull(left.union(right)),requireNotNull(left.difference(right)))
                assertEquals(4.0, left.getArea()); assertEquals(4.0, right.getArea())
                result
            }
        }
        for ((geometry, area) in outputs.zip(listOf(1.0,7.0,3.0))) geometry.use { assertEquals(area,it.getArea(),1e-9); assertTrue(it.isValid()) }
        r.createGeometryFromWkt(square).use { left ->
            r.createGeometryFromWkt("POINT (99 99)").use { right ->
                val none = left.intersection(right); if (none != null) none.use { assertTrue(it.isEmpty()) }
            }
            val empty = left.difference(left); if (empty != null) empty.use { assertTrue(it.isEmpty()) }
        }
    }

    @Test fun overlaysRejectKnownCrsMismatchInvalidTopologyAndClosedInputs() = runTest(timeout = 120.seconds) {
        val r = Gdal4k.runtime()
        r.createSpatialReference("EPSG:3857").use { projected ->
            r.createSpatialReference("EPSG:4326").use { geographic ->
                r.createGeometryFromWkt(square, projected).use { left ->
                    r.createGeometryFromWkt(square, geographic).use { right ->
                        assertFails { left.intersection(right) }; assertFails { left.union(right) }; assertFails { left.difference(right) }
                        right.assignSpatialReference(projected)
                        requireNotNull(left.intersection(right)).use { assertEquals(4.0,it.getArea()) }
                    }
                    r.createGeometryFromWkt("POLYGON ((0 0,2 2,0 2,2 0,0 0))").use { invalid ->
                        assertFalse(invalid.isValid()); assertFails { left.intersection(invalid) }
                    }
                    val closed = r.createGeometryFromWkt(square); closed.close()
                    assertFails { left.union(closed) }; assertEquals(4.0,left.getArea())
                    assertFails { closed.difference(left) }
                }
            }
        }
    }

    @Test fun spatialFiltersComposeResetAndCopyTheirInputs() = runTest(timeout = 120.seconds) {
        val r = Gdal4k.runtime()
        var retained: Geometry? = null
        points().use { dataset ->
            requireNotNull(dataset.getLayer(0)).use { layer ->
                assertEquals(3,layer.getFeatureCount(true)); assertNull(layer.getSpatialFilter())
                r.createGeometryFromWkt("POLYGON ((-2 -2,2 -2,2 2,-2 2,-2 -2))").use { filter ->
                    assertEquals(0,layer.setSpatialFilter(filter)); filter.empty()
                }
                assertEquals(1,layer.getFeatureCount(true))
                retained = requireNotNull(layer.getSpatialFilter())
                requireNotNull(layer.getSpatialFilter()).use { it.empty() }
                assertEquals(1,layer.getFeatureCount(true))
                assertEquals(0,layer.setAttributeFilter("name = 'b'")); assertEquals(0,layer.getFeatureCount(true))
                requireNotNull(layer.getFeature(20)).use { assertEquals("b",it.getFieldAsString("name")) }
                assertEquals(0,layer.setAttributeFilter(""))
                requireNotNull(layer.getNextFeature()).use { assertEquals(10,it.getFID()) }
                assertEquals(0,layer.setSpatialFilterRect(9.0,9.0,11.0,11.0))
                requireNotNull(layer.getNextFeature()).use { assertEquals(20,it.getFID()) }
                assertFails { layer.setSpatialFilterRect(2.0,0.0,1.0,1.0) }
                assertFails { layer.setSpatialFilterRect(Double.NaN,0.0,1.0,1.0) }
                assertFails { layer.setSpatialFilterRect(0.0,0.0,Double.POSITIVE_INFINITY,1.0) }
                assertEquals(1,layer.getFeatureCount(true))
                r.createSpatialReference("EPSG:3857").use { srs ->
                    r.createGeometryFromWkt(square,srs).use { assertFails { layer.setSpatialFilter(it) } }
                }
                assertEquals(0,layer.setSpatialFilter(null)); assertEquals(3,layer.getFeatureCount(true));assertNull(layer.getSpatialFilter())
                val closed = r.createGeometryFromWkt(square);closed.close();assertFails { layer.setSpatialFilter(closed) }
            }
        }
        requireNotNull(retained).use { assertEquals(16.0,it.getArea()) }
    }

    @Test fun newGeometryPersistsThroughFeatureEditingAndOwnershipTransfer() = runTest(timeout = 120.seconds) {
        val r = Gdal4k.runtime();val path="/vsimem/$datasetOutputPrefix-ogr-edit.gpkg"
        requireNotNull(r.getDriver("GPKG")).use { driver ->
            try {
                val files = requireNotNull(driver.createVector(path)).use { ds ->
                    requireNotNull(ds.createLayer("items",geometryType=1)).use { layer ->
                        r.createFieldDefn("name",4).use { assertEquals(0,layer.createField(it)) }
                        val fid = r.createFeature(requireNotNull(layer.getLayerDefn())).use { feature ->
                            r.createGeometryFromGeoJson("{\"type\":\"Point\",\"coordinates\":[3,4]}").use { geometry ->
                                assertEquals(0,feature.setGeometry(geometry))
                            }
                            feature.setField("name","created");assertEquals(0,layer.createFeature(feature));feature.getFID()
                        }
                        requireNotNull(layer.getFeature(fid)).use { feature ->
                            val replacement=r.createGeometryFromWkt("POINT (8 9)")
                            assertEquals(0,feature.setGeometryDirectly(replacement));assertFails { replacement.getPoint(0) };replacement.close()
                            feature.setField("name","updated");assertEquals(0,layer.setFeature(feature))
                        }
                        assertEquals(0,ds.flushCache())
                        val table=requireNotNull(ds.createLayer("attributes",geometryType=100))
                        assertFails { table.setSpatialFilterRect(0.0,0.0,1.0,1.0) };table.close()
                        ds.translateVector(driver="GeoJSON",name="edited.geojson",arguments=listOf("items"))
                    }
                }
                r.openDataset(files).use { ds ->
                    requireNotNull(ds.getLayer(0)).use { layer ->
                        requireNotNull(layer.getNextFeature()).use { feature ->
                            assertEquals("updated",feature.getFieldAsString("name"))
                            val borrowed=requireNotNull(feature.getGeometryRef());val cloned=borrowed.cloneGeometry()
                            feature.close();assertFails { borrowed.getPoint(0) };borrowed.close()
                            cloned.use { assertContentEquals(doubleArrayOf(8.0,9.0,0.0),it.getPoint(0)) }
                        }
                    }
                }
            } finally { driver.delete(path) }
        }
    }
}
