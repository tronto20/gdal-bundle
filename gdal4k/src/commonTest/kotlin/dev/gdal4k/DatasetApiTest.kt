package dev.gdal4k

import dev.gdal4k.runtime.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

internal val datasetOutputPrefix = "gdal4k-" + kotlin.random.Random.nextLong().toULong().toString(16)
internal expect fun datasetOutputPath(name: String): String

class DatasetApiTest {
    @Test fun metadataAndCrs() = runTest(timeout = 120.seconds) {
        val runtime = Gdal4k.runtime()
        requireNotNull(runtime.getDriver("MEM")).use { driver ->
            requireNotNull(driver.create("", 4, 2, 1, GdalDataType.Byte)).use { ds ->
                ds.setDescription("영상 🛰"); assertEquals("영상 🛰", ds.getDescription())
                assertNull(ds.getMetadataItem("missing"))
                assertEquals(0, ds.setMetadata(listOf("NAME=영상", "VALUE=a=b"), "custom"))
                assertEquals("a=b", ds.getMetadataItem("VALUE", "custom"))
                assertTrue("custom" in ds.getMetadataDomainList())
                assertEquals(0, ds.setMetadataItem("NAME", null, "custom")); assertNull(ds.getMetadataItem("NAME", "custom"))
                assertEquals(listOf("VALUE=a=b"), ds.getMetadata("custom"))
                runtime.createSpatialReference("EPSG:4326").use { srs -> assertEquals(0, ds.setSpatialRef(srs)) }
                val copy = requireNotNull(ds.getSpatialRef())
                assertEquals(0, ds.setSpatialRef(null)); assertNull(ds.getSpatialRef())
                copy.use { assertTrue(it.isGeographic()) }
                assertEquals(1, ds.getAccess())
                assertEquals(0, ds.addBand(GdalDataType.Byte)); assertEquals(2, ds.getRasterCount())
                val band=requireNotNull(ds.getRasterBand(1));val previousMask=requireNotNull(band.getMaskBand())
                assertEquals(0, ds.createMaskBand(2));assertFails { previousMask.getXSize() };previousMask.close();band.close()
                ds.clearStatistics()
            }
        }
    }
    @Test fun multibandWindowsAndTypes() = runTest(timeout = 120.seconds) {
        val r = Gdal4k.runtime()
        requireNotNull(r.getDriver("MEM")).use { driver ->
            requireNotNull(driver.create("", 4, 2, 2, GdalDataType.Float64)).use { ds ->
                val pixels = doubleArrayOf(0.0,2.0,4.0,6.0,8.0,10.0,12.0,14.0,20.0,22.0,24.0,26.0,28.0,30.0,32.0,34.0)
                assertEquals(0, ds.writeRaster(pixels))
                val result = DoubleArray(6) { -1.0 }
                assertEquals(0, ds.readRaster(result, RasterIOOptions(xOffset=1,xSize=2,bands=intArrayOf(2))))
                assertContentEquals(doubleArrayOf(22.0,24.0,30.0,32.0,-1.0,-1.0), result)
                val average = DoubleArray(4)
                assertEquals(0, ds.readRaster(average, RasterIOOptions(bufferWidth=2,bufferHeight=1,resampling=RasterResampling.Average)))
                assertContentEquals(doubleArrayOf(5.0,9.0,25.0,29.0), average)
                val bad = ByteArray(3) { 99 }
                assertFails { ds.readRaster(bad) }; assertTrue(bad.all { it == 99.toByte() })
                assertFails { ds.readRaster(DoubleArray(8), RasterIOOptions(bands=intArrayOf(3))) }
                assertFails { ds.readRaster(DoubleArray(8), RasterIOOptions(xSize=Int.MAX_VALUE)) }
                assertEquals(0, ds.readRaster(ByteArray(16))); assertEquals(0, ds.readRaster(ShortArray(16)))
                assertEquals(0, ds.readRaster(IntArray(16))); assertEquals(0, ds.readRaster(LongArray(16)))
                assertEquals(0, ds.readRaster(FloatArray(16)))
                assertEquals(0, ds.writeRaster(byteArrayOf(-1), RasterIOOptions(xSize=1,ySize=1,bands=intArrayOf(1))))
                val one = DoubleArray(1); ds.readRaster(one,RasterIOOptions(xSize=1,ySize=1,bands=intArrayOf(1)))
                assertEquals(255.0, one[0])
                assertEquals(0, ds.writeRaster(shortArrayOf(-2),RasterIOOptions(xSize=1,ySize=1,bands=intArrayOf(1))))
                assertEquals(0, ds.writeRaster(intArrayOf(42),RasterIOOptions(xSize=1,ySize=1,bands=intArrayOf(1))))
                assertEquals(0, ds.writeRaster(longArrayOf(43),RasterIOOptions(xSize=1,ySize=1,bands=intArrayOf(1))))
                assertEquals(0, ds.writeRaster(floatArrayOf(1.5f),RasterIOOptions(xSize=1,ySize=1,bands=intArrayOf(1))))
            }
            requireNotNull(driver.create("",2,1,1,GdalDataType.Int64)).use { ds ->
                val values=longArrayOf(Long.MIN_VALUE,Long.MAX_VALUE)
                ds.writeRaster(values); val result=LongArray(2); ds.readRaster(result); assertContentEquals(values,result)
            }
        }
    }
    @Test fun directPathOutputs() = runTest(timeout = 120.seconds) {
        val r=Gdal4k.runtime(); val path=datasetOutputPath("dataset-api.tif"); val warped=datasetOutputPath("dataset-warp.tif"); val cog=datasetOutputPath("dataset-cog.tif")
        requireNotNull(r.getDriver("GTiff")).use { tiff ->
            try {
                requireNotNull(r.getDriver("MEM")).use { driver ->
                    requireNotNull(driver.create("",2,2,1,GdalDataType.Byte)).use { ds ->
                        ds.writeRaster(byteArrayOf(1,2,3,-1)); ds.setGeoTransform(doubleArrayOf(0.0,1.0,0.0,2.0,0.0,-1.0))
                        runtimeCrs(r,ds); ds.translateTo(path, "GTiff", listOf("-co","COMPRESS=JXL","-co","JXL_LOSSLESS=YES"))
                        ds.translateTo(cog, "COG", listOf("-co","COMPRESS=JXL","-co","JXL_LOSSLESS=YES"))
                        ds.warpTo(warped,"EPSG:4326",arguments=listOf("-ts","2","2","-r","near"))
                    }
                }
                for (p in listOf(path,warped,cog)) r.openDataset(p).use { ds ->
                    val pixels=ByteArray(4); assertEquals(0,ds.readRaster(pixels)); assertContentEquals(byteArrayOf(1,2,3,-1),pixels)
                }
            } finally { tiff.delete(path); tiff.delete(warped); tiff.delete(cog) }
        }
    }
    private suspend fun runtimeCrs(r: GdalRuntime, ds: Dataset) {
        r.createSpatialReference("EPSG:4326").use { it.setAxisMappingStrategy(0); assertEquals(0,ds.setSpatialRef(it)) }
    }
    @Test fun layersSqlAndTransactions() = runTest(timeout = 120.seconds) {
        val r=Gdal4k.runtime(); val path="/vsimem/dataset-api.gpkg";val output=datasetOutputPath("dataset-api.geojson")
        requireNotNull(r.getDriver("GPKG")).use { driver ->
            try { requireNotNull(driver.createVector(path)).use { ds ->
                val layer=requireNotNull(ds.createLayer("items",geometryType=100))
                r.createFieldDefn("name",4).use { assertEquals(0,layer.createField(it)) }
                assertEquals(0,ds.flushCache())
                assertEquals(0,ds.startTransaction())
                r.createFeature(requireNotNull(layer.getLayerDefn())).use { f -> f.setField("name","first"); assertEquals(0,layer.createFeature(f)) }
                assertEquals(0,ds.commitTransaction())
                layer.resetReading(); requireNotNull(layer.getNextFeature()).use { assertEquals("first",it.getFieldAsString("name")) }
                assertEquals(0,ds.startTransaction())
                r.createFeature(requireNotNull(layer.getLayerDefn())).use { f -> f.setField("name","rolled-back"); assertEquals(0,layer.createFeature(f)) }
                assertEquals(0,ds.rollbackTransaction()); assertEquals(1,layer.getFeatureCount(true))
                repeat(3) { requireNotNull(ds.executeSQL("SELECT name FROM items",dialect="SQLITE")).use { result ->
                    requireNotNull(result.getNextFeature()).use { assertEquals("first",it.getFieldAsString("name")) }
                } }
                assertFails { ds.executeSQL("SELECT * FROM missing_table") }
                assertEquals(1,layer.getFeatureCount(true))
                val clone=requireNotNull(ds.copyLayer(layer,"copy")); assertEquals(1,clone.getFeatureCount(true))
                val activeResult=requireNotNull(ds.executeSQL("SELECT * FROM copy"))
                assertEquals(0,ds.deleteLayer(1)); assertFails { clone.getFeatureCount() };clone.close()
                assertFails { activeResult.getFeatureCount() };activeResult.close()
                ds.translateVectorTo(output,arguments=listOf("items"))
                val result=requireNotNull(ds.executeSQL("SELECT * FROM items"));ds.close()
                assertFails { result.getFeatureCount() };result.close()
            } } finally { driver.delete(path) }
        }
        requireNotNull(r.getDriver("GeoJSON")).use { driver ->
            try { r.openDataset(output).use { ds -> assertEquals(1,requireNotNull(ds.getLayer(0)).getFeatureCount()) } }
            finally { driver.delete(output) }
        }
    }
    @Test fun multidimensionalSlicesAndLifetime() = runTest(timeout = 120.seconds) {
        val r=Gdal4k.runtime()
        requireNotNull(r.getDriver("MEM")).use { driver ->
            requireNotNull(driver.createMultidimensional("")).use { ds ->
                val root=requireNotNull(ds.getRootGroup()); val group=requireNotNull(root.createGroup("science"))
                val y=requireNotNull(group.createDimension("y",2)); val x=requireNotNull(group.createDimension("x",3))
                val array=requireNotNull(group.createArray("values",listOf(y,x),GdalDataType.Float64))
                assertEquals(GdalDataType.Float64,array.getDataType())
                assertTrue(array.write(longArrayOf(0,0),longArrayOf(2,3),doubleArrayOf(1.0,2.0,3.0,4.0,5.0,6.0)))
                assertContentEquals(doubleArrayOf(2.0,3.0,5.0,6.0),array.read(longArrayOf(0,1),longArrayOf(2,2)))
                assertFails { array.read(longArrayOf(0,2),longArrayOf(2,2)) }
                assertEquals(listOf("science"),root.getGroupNames()); assertEquals(listOf("values"),group.getArrayNames())
                assertEquals(listOf(2L,3L),array.getDimensions().map { d -> d.use { it.getSize() } })
                val reopened=requireNotNull(group.openArray("values"));assertEquals("values",reopened.getName());reopened.close()
                ds.close(); assertFails { array.getName() };assertFails { x.getSize() };root.close();array.close()
            }
        }
    }
    @Test fun persistedRelationships() = runTest(timeout = 120.seconds) {
        val r=Gdal4k.runtime(); val path="/vsimem/dataset-relations.gpkg"
        requireNotNull(r.getDriver("GPKG")).use { driver ->
            try { requireNotNull(driver.createVector(path)).use { ds ->
                requireNotNull(ds.createLayer("left",geometryType=1)).close()
                requireNotNull(ds.createLayer("right",geometryType=1)).close()
                assertEquals(0,ds.flushCache())
                r.createRelationship("link","left","right",3).use { relation ->
                    relation.setLeftTableFields(listOf("fid"));relation.setRightTableFields(listOf("fid"))
                    ds.addRelationship(relation)
                }
                val name=ds.getRelationshipNames().single()
                val copy=requireNotNull(ds.getRelationship(name))
                assertEquals("left",copy.getLeftTableName());assertEquals("right",copy.getRightTableName())
                ds.updateRelationship(copy);ds.deleteRelationship(name)
                assertTrue(ds.getRelationshipNames().isEmpty())
                copy.use { assertEquals(name,it.getName()) }
            } } finally { driver.delete(path) }
        }
    }
    @Test fun domainsAndRelationships() = runTest(timeout = 120.seconds) {
        val r=Gdal4k.runtime()
        requireNotNull(r.getDriver("MEM")).use { driver -> requireNotNull(driver.createVector("")).use { ds ->
            r.createGlobFieldDomain("code","code pattern",4,"[A-Z]*").use { domain ->
                assertEquals("[A-Z]*",domain.getGlob()); ds.addFieldDomain(domain)
                assertTrue("code" in ds.getFieldDomainNames())
                val independent=requireNotNull(ds.getFieldDomain("code"));ds.deleteFieldDomain("code")
                independent.use { assertEquals("code",it.getName());ds.addFieldDomain(it);ds.updateFieldDomain(it) }
            }
            ds.deleteFieldDomain("code");assertFails { ds.deleteFieldDomain("code") }
            // MEM does not implement relationships. A rejection must preserve the caller's handle.
            r.createRelationship("items_link","left","right").use { relationship ->
                relationship.setLeftTableFields(listOf("id"));relationship.setRightTableFields(listOf("id"))
                assertEquals(listOf("id"),relationship.getLeftTableFields())
                assertFails { ds.addRelationship(relationship) };assertEquals("items_link",relationship.getName())
            }
        } }
    }
}
