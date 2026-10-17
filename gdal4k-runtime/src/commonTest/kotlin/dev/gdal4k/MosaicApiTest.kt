package dev.gdal4k

import dev.gdal4k.runtime.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class MosaicApiTest {
    private suspend fun tile(r: GdalRuntime, x: Double, pixels: ByteArray, width: Int = 2): Dataset {
        return requireNotNull(r.getDriver("MEM")).use { driver ->
            requireNotNull(driver.create("",width,pixels.size/width,1,GdalDataType.Byte)).also { ds ->
                ds.setGeoTransform(doubleArrayOf(x,1.0,0.0,2.0,0.0,-1.0))
                r.createSpatialReference("EPSG:4326").use { s -> s.setAxisMappingStrategy(0);assertEquals(0,ds.setSpatialRef(s)) }
                assertEquals(0,ds.writeRaster(pixels))
            }
        }
    }
    private suspend fun pixels(ds: Dataset): ByteArray = ByteArray(ds.getRasterXSize()*ds.getRasterYSize()).also { assertEquals(0,ds.readRaster(it)) }

    @Test fun adjacentVirtualMosaicAndSeparateBands() = runTest(timeout=120.seconds) {
        val r=Gdal4k.runtime()
        tile(r,0.0,byteArrayOf(1,2,3,4)).use { left -> tile(r,2.0,byteArrayOf(5,6,7,8)).use { right ->
            r.buildVRT(listOf(left,right)).use { vrt ->
                assertEquals("VRT",vrt.getDriverShortName());assertEquals(4,vrt.getRasterXSize());assertEquals(2,vrt.getRasterYSize())
                assertContentEquals(byteArrayOf(1,2,5,6,3,4,7,8),pixels(vrt))
                assertContentEquals(doubleArrayOf(0.0,1.0,0.0,2.0,0.0,-1.0),vrt.getGeoTransform())
                vrt.translate(driver = "COG").let { files -> r.openDataset(files).use { assertContentEquals(pixels(vrt),pixels(it)) } }
            }
            r.buildVRT(listOf(left,right),listOf("-separate","-vrtnodata","99")).use { vrt ->
                assertEquals(2,vrt.getRasterCount())
                val out=ByteArray(8)
                requireNotNull(vrt.getRasterBand(1)).use { b -> assertEquals(0,b.readRaster(out));assertContentEquals(byteArrayOf(1,2,99,99,3,4,99,99),out) }
                requireNotNull(vrt.getRasterBand(2)).use { b -> assertEquals(0,b.readRaster(out));assertContentEquals(byteArrayOf(99,99,5,6,99,99,7,8),out) }
            }
        } }
    }

    @Test fun overlapOrderAndNoData() = runTest(timeout=120.seconds) {
        val r=Gdal4k.runtime()
        tile(r,0.0,byteArrayOf(1,2,3,4)).use { a -> tile(r,0.0,byteArrayOf(9,0,0,8)).use { b ->
            requireNotNull(b.getRasterBand(1)).use { assertEquals(0,it.setNoDataValue(0.0)) }
            r.buildVRT(listOf(a,b), "-resolution", "highest").use { assertContentEquals(byteArrayOf(9,2,3,8),pixels(it)) }
            r.buildVRT(listOf(b,a)).use { assertContentEquals(byteArrayOf(1,2,3,4),pixels(it)) }
            r.openDataset(r.warp(listOf(a,b),"EPSG:4326","-r","near")).use { assertContentEquals(byteArrayOf(9,2,3,8),pixels(it)) }
            r.openDataset(r.warp(listOf(b,a),"EPSG:4326")).use { assertContentEquals(byteArrayOf(1,2,3,4),pixels(it)) }
        } }
    }

    @Test fun nativeInputsWorkspacesAndNestedVrtLifetime() = runTest(timeout=120.seconds) {
        val r=Gdal4k.runtime()
        val one=tile(r,0.0,byteArrayOf(1,2,3,4)).use { r.openDataset(it.translate(name="one.tif")) }
        val two=tile(r,2.0,byteArrayOf(5,6,7,8)).use { r.openDataset(it.translate(name="two.tif")) }
        val inputPaths=listOf(one.getDescription(),two.getDescription())
        val first=r.buildVRT(listOf(one,two));val sibling=r.buildVRT(listOf(one,two));val nested=r.buildVRT(listOf(first))
        val band=requireNotNull(one.getRasterBand(1))
        one.close();two.close();assertFails { one.getRasterXSize() };assertFails { band.getXSize() };band.close()
        inputPaths.forEach { assertTrue(r.readMemoryFile(it).isNotEmpty()) }
        val expected=byteArrayOf(1,2,5,6,3,4,7,8)
        assertContentEquals(expected,pixels(first));assertContentEquals(expected,pixels(nested))
        first.close();assertFails { first.getRasterXSize() };assertContentEquals(expected,pixels(nested))
        sibling.close();inputPaths.forEach { assertTrue(r.readMemoryFile(it).isNotEmpty()) }
        nested.close();inputPaths.forEach { assertFails { r.readMemoryFile(it) } }
        one.close();two.close();first.close();sibling.close();nested.close()
    }

    @Test fun multiInputWarpReprojectsAndWritesDirectly() = runTest(timeout=120.seconds) {
        val r=Gdal4k.runtime();val path=datasetOutputPath("multi-warp.tif");val cogPath=datasetOutputPath("multi-warp-cog.tif")
        tile(r,0.0,byteArrayOf(1,2,3,4)).use { a -> tile(r,2.0,byteArrayOf(5,6,7,8)).use { b ->
            val options=listOf("-r","near","-ts","4","2")
            val files=r.warp(listOf(a,b),"EPSG:3857",driver="COG",name="mosaic.tif",arguments=options)
            r.openDataset(files).use { out ->
                assertEquals(4,out.getRasterXSize());assertEquals(2,out.getRasterYSize());assertTrue(out.getProjection().contains("3857"))
                assertContentEquals(byteArrayOf(1,2,5,6,3,4,7,8),pixels(out))
            }
            requireNotNull(r.getDriver("GTiff")).use { driver ->
                try {
                    r.warpTo(listOf(a,b),path,"EPSG:3857",arguments=options)
                    val mounted=r.openDataset(path);val virtual=r.buildVRT(listOf(mounted));mounted.close()
                    virtual.use { assertContentEquals(byteArrayOf(1,2,5,6,3,4,7,8),pixels(it)) }
                    r.warpTo(listOf(a,b),cogPath,"EPSG:3857",driver="COG",arguments=options)
                    r.openDataset(cogPath).use { assertContentEquals(byteArrayOf(1,2,5,6,3,4,7,8),pixels(it)) }
                } finally { driver.delete(path);driver.delete(cogPath) }
            }
            assertContentEquals(byteArrayOf(1,2,3,4),pixels(a));assertContentEquals(byteArrayOf(5,6,7,8),pixels(b))
        } }
    }

    @Test fun validationDoesNotConsumeInputs() = runTest(timeout=120.seconds) {
        val r=Gdal4k.runtime()
        assertFails { r.buildVRT(emptyList()) };assertFails { r.warp(emptyList(),"EPSG:4326") }
        tile(r,0.0,byteArrayOf(1,2,3,4)).use { a ->
            assertFails { r.buildVRT(listOf(a),listOf("--invalid-vrt-option")) }
            assertFails { r.warp(listOf(a),"EPSG:4326",arguments=listOf("--invalid-warp-option")) }
            assertFails { r.warp(listOf(a),"") };assertFails { r.warp(listOf(a),"EPSG:4326",driver="VRT") }
            assertFails { r.warpTo(listOf(a),"", "EPSG:4326") }
            assertFails { r.warp(listOf(a),"EPSG:4326",name="../escape.tif") }
            assertFails { r.buildVRT(listOf(a),listOf("bad\u0000option")) }
            val closed=tile(r,2.0,byteArrayOf(5,6,7,8));closed.close()
            assertFails { r.buildVRT(listOf(a,closed)) };assertFails { r.warp(listOf(a,closed),"EPSG:4326") }
            requireNotNull(r.getDriver("MEM")).use { driver -> requireNotNull(driver.create("",2,2,2,GdalDataType.Byte)).use { incompatible ->
                incompatible.setGeoTransform(doubleArrayOf(2.0,1.0,0.0,2.0,0.0,-1.0));incompatible.setProjection(a.getProjection())
                assertFails { r.buildVRT(listOf(a,incompatible)) }
            } }
            r.buildVRT(listOf(a)).use { assertContentEquals(byteArrayOf(1,2,3,4),pixels(it)) }
        }
    }

    @Test fun runtimeShutdownReleasesDeferredSources() = runTest(timeout=120.seconds) {
        val r=Gdal4k.runtime();val a=tile(r,0.0,byteArrayOf(1,2,3,4));val vrt=r.buildVRT(listOf(a));a.close()
        assertContentEquals(byteArrayOf(1,2,3,4),pixels(vrt));Gdal4k.close()
        assertFails { vrt.getRasterXSize() };vrt.close();assertEquals("3120400",Gdal4k.runtime().versionInfo("VERSION_NUM"))
    }
}
