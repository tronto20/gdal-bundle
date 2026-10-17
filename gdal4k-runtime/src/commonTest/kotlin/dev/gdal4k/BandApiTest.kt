package dev.gdal4k

import dev.gdal4k.runtime.*
import kotlinx.coroutines.test.runTest
import kotlin.math.sqrt
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class BandApiTest {
    private suspend fun <T> memory(type: Int = GdalDataType.Float64, block: suspend (Dataset, Band) -> T): T {
        val r = Gdal4k.runtime()
        return requireNotNull(r.getDriver("MEM")).use { driver ->
            requireNotNull(driver.create("", 2, 2, 1, type)).use { ds ->
                requireNotNull(ds.getRasterBand(1)).use { band -> block(ds, band) }
            }
        }
    }

    @Test fun metadataCalibrationAndRawValues() = runTest(timeout = 120.seconds) { memory { _, b ->
        assertEquals(1, b.getBandNumber()); assertEquals(1, b.getAccess())
        b.setDescription("영상 🛰 band"); assertEquals("영상 🛰 band", b.getDescription())
        assertEquals(0, b.setMetadata(listOf("KEY=value", "LANG=한글"), "custom"))
        assertTrue("custom" in b.getMetadataDomainList()); assertTrue("LANG=한글" in b.getMetadata("custom"))
        assertEquals("value", b.getMetadataItem("KEY", "custom"))
        assertEquals(0, b.setMetadataItem("KEY", null, "custom")); assertNull(b.getMetadataItem("KEY", "custom"))
        assertFails { b.setDescription("bad\u0000name") }
        assertEquals(0, b.setScale(2.5)); assertEquals(0, b.setOffset(-7.0))
        assertEquals(2.5, b.getScale()); assertEquals(-7.0, b.getOffset())
        assertEquals(0, b.fill(4.0)); val raw=DoubleArray(4)
        assertEquals(0,b.readRaster(raw)); assertContentEquals(doubleArrayOf(4.0,4.0,4.0,4.0),raw)
    } }

    @Test fun exactIntegerNoData() = runTest(timeout = 120.seconds) {
        memory(GdalDataType.Int64) { _, b ->
            assertNull(b.getNoDataValueAsInt64())
            for (v in listOf(Long.MIN_VALUE, 9007199254740993L, Long.MAX_VALUE)) {
                assertEquals(0,b.setNoDataValueAsInt64(v)); assertEquals(v,b.getNoDataValueAsInt64())
            }
            assertFails { b.setNoDataValueAsUInt64(ULong.MAX_VALUE) }
            assertEquals(Long.MAX_VALUE,b.getNoDataValueAsInt64())
            assertEquals(0,b.deleteNoDataValue()); assertNull(b.getNoDataValueAsInt64())
        }
        memory(GdalDataType.UInt64) { _, b ->
            assertNull(b.getNoDataValueAsUInt64())
            for (v in listOf(0uL, 9007199254740993uL, ULong.MAX_VALUE)) {
                assertEquals(0,b.setNoDataValueAsUInt64(v)); assertEquals(v,b.getNoDataValueAsUInt64())
            }
            assertFails { b.getNoDataValueAsInt64() }; assertFails { b.setNoDataValueAsInt64(-1) }
            assertEquals(ULong.MAX_VALUE,b.getNoDataValueAsUInt64())
            assertEquals(0,b.deleteNoDataValue()); assertNull(b.getNoDataValueAsUInt64())
        }
    }

    @Test fun statisticsCacheComputationAndNoData() = runTest(timeout = 120.seconds) { memory { ds, b ->
        assertEquals(0,b.writeRaster(doubleArrayOf(1.0,2.0,3.0,-9999.0))); assertEquals(0,b.setNoDataValue(-9999.0))
        assertNull(b.getStatistics())
        val stats=b.computeStatistics()
        assertEquals(1.0,stats.minimum);assertEquals(3.0,stats.maximum);assertEquals(2.0,stats.mean)
        assertEquals(sqrt(2.0/3),stats.standardDeviation,1e-12)
        assertNotNull(b.getStatistics());assertContentEquals(doubleArrayOf(1.0,3.0),b.computeRasterMinMax())
        assertEquals(0,b.setStatistics(10.0,20.0,15.0,2.0))
        assertEquals(RasterStatistics(10.0,20.0,15.0,2.0),b.getStatistics())
        assertEquals(10.0,b.getMinimum());assertEquals(20.0,b.getMaximum())
        assertEquals(2.0,b.computeStatistics().mean) // recompute rather than reusing injected cache
        ds.clearStatistics() // Driver-specific; MEM does not clear its band metadata here.
        assertEquals(0,b.setMetadata(emptyList()));assertNull(b.getStatistics());assertEquals(2.0,b.getStatistics(force=true)?.mean)
        assertFails { b.setStatistics(20.0,10.0,15.0,2.0) }
        assertFails { b.setStatistics(0.0,1.0,0.5,Double.NaN) }
        assertFails { b.setStatistics(0.0,1.0,0.5,-1.0) }
    } }

    @Test fun histogramOutliersNoDataAndValidation() = runTest(timeout = 120.seconds) {
        val r=Gdal4k.runtime()
        requireNotNull(r.getDriver("MEM")).use { d -> requireNotNull(d.create("",6,1,1,GdalDataType.Int16)).use { ds ->
            requireNotNull(ds.getRasterBand(1)).use { b ->
                assertEquals(0,b.writeRaster(shortArrayOf(-1,0,1,2,3,4)))
                assertContentEquals(longArrayOf(1,1,1,1),b.getHistogram(-0.5,3.5,4))
                assertContentEquals(longArrayOf(2,1,1,2),b.getHistogram(-0.5,3.5,4,includeOutOfRange=true))
                // GDAL may retain cached histograms after metadata mutations. Test NoData
                // with a fresh band so this assertion does not depend on invalidation policy.
                assertFails { b.getHistogram(1.0,1.0,4) };assertFails { b.getHistogram(0.0,1.0,0) }
                assertFails { b.getHistogram(Double.NaN,1.0,4) }
                assertFails { b.setDefaultHistogram(0.0,1.0,longArrayOf(-1)) }
                assertFails { b.setDefaultHistogram(0.0,1.0,longArrayOf()) }
            }
        } }
    }

    @Test fun histogramExcludesNoData() = runTest(timeout = 120.seconds) { memory { _, b ->
        assertEquals(0,b.writeRaster(doubleArrayOf(0.0,1.0,2.0,3.0)))
        assertEquals(0,b.setNoDataValue(2.0))
        assertContentEquals(longArrayOf(1,1,0,1),b.getHistogram(-0.5,3.5,4))
    } }

    @Test fun resamplingTypedBuffersAndWindows() = runTest(timeout = 120.seconds) { memory { _, b ->
        assertEquals(0,b.writeRaster(doubleArrayOf(0.0,2.0,4.0,6.0)))
        val average=BandRasterIOOptions(bufferWidth=1,bufferHeight=1,resampling=RasterResampling.Average)
        val result=doubleArrayOf(-1.0,99.0);assertEquals(0,b.readRaster(average,result));assertContentEquals(doubleArrayOf(3.0,99.0),result)
        val bytes=ByteArray(1);assertEquals(0,b.readRaster(average,bytes));assertEquals(3,bytes[0].toInt())
        val shorts=ShortArray(1);assertEquals(0,b.readRaster(average,shorts));assertEquals(3,shorts[0].toInt())
        val ints=IntArray(1);assertEquals(0,b.readRaster(average,ints));assertEquals(3,ints[0])
        val longs=LongArray(1);assertEquals(0,b.readRaster(average,longs));assertEquals(3L,longs[0])
        val floats=FloatArray(1);assertEquals(0,b.readRaster(average,floats));assertEquals(3f,floats[0])
        val window=BandRasterIOOptions(xOffset=1,yOffset=1);val pixel=DoubleArray(1)
        assertEquals(0,b.readRaster(window,pixel));assertEquals(6.0,pixel[0])
        assertEquals(0,b.writeRaster(window,byteArrayOf(8)))
        assertEquals(0,b.writeRaster(window,shortArrayOf(9)))
        assertEquals(0,b.writeRaster(window,intArrayOf(10)))
        assertEquals(0,b.writeRaster(window,longArrayOf(11)))
        assertEquals(0,b.writeRaster(window,floatArrayOf(12f)))
        assertEquals(0,b.writeRaster(window,doubleArrayOf(13.0)))
        assertEquals(0,b.readRaster(window,pixel));assertEquals(13.0,pixel[0])
        assertFails { b.readRaster(BandRasterIOOptions(xOffset=2),result) }
        assertFails { b.readRaster(BandRasterIOOptions(xOffset=Int.MIN_VALUE),result) }
        assertFails { b.readRaster(BandRasterIOOptions(bufferWidth=65536,bufferHeight=65536),result) }
        assertFails { b.readRaster(BandRasterIOOptions(bufferWidth=-1),result) }
        assertFails { b.readRaster(BandRasterIOOptions(),DoubleArray(3)) }
        assertFails { b.writeRaster(average,doubleArrayOf(1.0)) }
        assertEquals(0,b.readRaster(window,pixel));assertEquals(13.0,pixel[0])
    } }

    @Test fun persistedBandPropertiesAndHistogramSnapshot() = runTest(timeout = 120.seconds) {
        val r=Gdal4k.runtime();val path="/vsimem/$datasetOutputPrefix-band-properties.tif"
        requireNotNull(r.getDriver("GTiff")).use { d -> try {
            requireNotNull(d.create(path,2,2,1,GdalDataType.UInt64)).use { ds -> requireNotNull(ds.getRasterBand(1)).use { b ->
                b.setDescription("persisted 🛰");assertEquals(0,b.setScale(0.25));assertEquals(0,b.setOffset(3.0))
                assertEquals(0,b.setNoDataValueAsUInt64(ULong.MAX_VALUE));assertEquals(0,b.fill(2.0))
                assertNull(b.getDefaultHistogram())
                assertEquals(4L,assertNotNull(b.getDefaultHistogram(force=true)).counts.sum())
                assertEquals(0,b.setDefaultHistogram(-0.5,3.5,longArrayOf(0,0,9007199254740993L,0)))
                val first=assertNotNull(b.getDefaultHistogram());first.counts[2]=0
                assertEquals(9007199254740993L,assertNotNull(b.getDefaultHistogram()).counts[2])
                b.flushCache()
            } }
            r.openDataset(path).use { ds -> val b=requireNotNull(ds.getRasterBand(1))
                assertEquals("persisted 🛰",b.getDescription());assertEquals(0.25,b.getScale());assertEquals(3.0,b.getOffset())
                assertEquals(ULong.MAX_VALUE,b.getNoDataValueAsUInt64());assertEquals(0,b.getAccess())
                val h=assertNotNull(b.getDefaultHistogram());assertEquals(-0.5,h.minimum);assertEquals(3.5,h.maximum)
                assertContentEquals(longArrayOf(0,0,9007199254740993L,0),h.counts)
                ds.close();assertFails { b.computeStatistics() };b.close()
            }
        } finally { d.delete(path) } }
    }
}
