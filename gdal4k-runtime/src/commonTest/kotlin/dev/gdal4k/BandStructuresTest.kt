package dev.gdal4k

import dev.gdal4k.runtime.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class BandStructuresTest {
    private suspend fun <T> memory(block: suspend (Dataset, Band) -> T): T {
        val r=Gdal4k.runtime()
        return requireNotNull(r.getDriver("MEM")).use { d -> requireNotNull(d.create("",4,2,1,GdalDataType.Byte)).use { ds ->
            requireNotNull(ds.getRasterBand(1)).use { b -> block(ds,b) }
        } }
    }

    @Test fun paletteCopiesRampsAndLifetime() = runTest(timeout=120.seconds) {
        val r=Gdal4k.runtime()
        val snapshot=memory { _,b ->
            assertNull(b.getColorTable())
            r.createColorTable().use { p ->
                assertEquals(PaletteInterpretation.RGB,p.getPaletteInterpretation())
                p.createColorRamp(0,ColorEntry(0,0,0,255),2,ColorEntry(200,100,50,255))
                assertEquals(3,p.getColorEntryCount());assertEquals(ColorEntry(100,50,25,255),p.getColorEntryAsRGB(1))
                p.setColorEntry(1,ColorEntry(11,22,33,44));assertEquals(0,b.setColorTable(p))
                p.cloneColorTable().use { copy -> copy.setColorEntry(1,ColorEntry(77));assertEquals(ColorEntry(11,22,33,44),p.getColorEntry(1)) }
                assertNull(p.getColorEntry(99));assertFails { p.getColorEntry(-1) }
                assertFails { p.createColorRamp(2,ColorEntry(1),1,ColorEntry(2)) }
                assertFails { p.setColorEntry(Int.MAX_VALUE,ColorEntry(1)) }
            }
            val detached=requireNotNull(b.getColorTable());detached.setColorEntry(1,ColorEntry(99))
            requireNotNull(b.getColorTable()).use { assertEquals(ColorEntry(11,22,33,44),it.getColorEntry(1)) }
            assertEquals(0,b.setColorTable(null));assertNull(b.getColorTable());detached
        }
        snapshot.use { assertEquals(ColorEntry(99),it.getColorEntry(1)) }
        assertFails { snapshot.getColorEntryCount() };assertFails { r.createColorTable(4) }
        assertFails { ColorEntry(32768) }
    }

    @Test fun ratColumnsWindowsAndValidation() = runTest(timeout=120.seconds) {
        Gdal4k.runtime().createRasterAttributeTable().use { t ->
            assertEquals(0,t.getColumnCount());assertEquals(0,t.getRowCount());assertNull(t.getLinearBinning())
            assertEquals(0,t.createColumn("class",RasterFieldType.Integer,RasterFieldUsage.MinMax))
            assertEquals(0,t.createColumn("measure",RasterFieldType.Real))
            assertEquals(0,t.createColumn("이름 🛰",RasterFieldType.String,RasterFieldUsage.Name))
            t.setRowCount(4);assertEquals(4,t.getRowCount());assertEquals(3,t.getColumnCount())
            assertEquals("이름 🛰",t.getNameOfCol(2));assertEquals(RasterFieldType.String,t.getTypeOfCol(2))
            assertEquals(RasterFieldUsage.Name,t.getUsageOfCol(2));assertEquals(2,t.getColOfUsage(RasterFieldUsage.Name))
            assertEquals(-1,t.getColOfUsage(RasterFieldUsage.Blue))
            assertEquals(0,t.writeIntegerValues(0,0,intArrayOf(Int.MIN_VALUE,2,3,Int.MAX_VALUE)))
            assertEquals(0,t.writeDoubleValues(1,1,doubleArrayOf(1.25,2.5)))
            t.writeStringValues(2,1,listOf("한글 🛰","second"))
            assertContentEquals(intArrayOf(2,3),t.readIntegerValues(0,1,2))
            assertContentEquals(doubleArrayOf(1.25,2.5),t.readDoubleValues(1,1,2))
            assertEquals(listOf("한글 🛰","second"),t.readStringValues(2,1,2))
            t.setValueAsInt(1,0,17);t.setValueAsDouble(2,1,-3.75);t.setValueAsString(3,2,"tail")
            assertEquals(17,t.getValueAsInt(1,0));assertEquals(-3.75,t.getValueAsDouble(2,1));assertEquals("tail",t.getValueAsString(3,2))
            assertContentEquals(intArrayOf(),t.readIntegerValues(0,4,0));assertEquals(0,t.writeDoubleValues(1,4,doubleArrayOf()))
            assertEquals(emptyList(),t.readStringValues(2,4,0));t.writeStringValues(2,4,emptyList())
            assertFails { t.readIntegerValues(1,0,1) };assertFails { t.getValueAsInt(0,1) }
            assertFails { t.writeDoubleValues(0,0,doubleArrayOf(Double.MAX_VALUE)) }
            assertFails { t.setValueAsDouble(0,0,Double.NaN) };assertEquals(Int.MIN_VALUE,t.getValueAsInt(0,0))
            assertFalse(t.changesAreWrittenToFile())
            assertEquals(0,t.setTableType(RasterTableType.Athematic));assertEquals(RasterTableType.Athematic,t.getTableType())
            assertEquals(0,t.setLinearBinning(10.0,2.0));assertContentEquals(doubleArrayOf(10.0,2.0),t.getLinearBinning())
            assertEquals(1,t.getRowOfValue(12.5));assertEquals(-1,t.getRowOfValue(99.0))
            assertEquals(-1,t.getRowOfValue(Double.MAX_VALUE));assertEquals(-1,t.getRowOfValue(-Double.MAX_VALUE))
            assertFails { t.getRowOfValue(Double.NaN) };assertFails { t.getRowOfValue(Double.POSITIVE_INFINITY) }
            t.cloneTable().use { copy -> copy.setValueAsInt(1,0,88);assertEquals(17,t.getValueAsInt(1,0)) }
            assertFails { t.readIntegerValues(0,Int.MAX_VALUE,Int.MAX_VALUE) }
            assertFails { t.readDoubleValues(1,-1,1) };assertFails { t.readStringValues(2,0,-1) }
            assertFails { t.writeIntegerValues(0,3,intArrayOf(7,8)) };assertEquals(Int.MAX_VALUE,t.getValueAsInt(3,0))
            assertFails { t.writeStringValues(2,1,listOf("good","bad\u0000text")) };assertEquals("한글 🛰",t.getValueAsString(1,2))
            assertFails { t.getValueAsInt(4,0) };assertFails { t.getNameOfCol(3) };assertFails { t.setRowCount(-1) }
            assertFails { t.createColumn("bad",3) };assertFails { t.createColumn("bad\u0000name",0) }
            assertFails { t.setLinearBinning(0.0,0.0) };assertFails { t.setLinearBinning(Double.NaN,1.0) }
            assertFails { t.setTableType(2) };t.removeStatistics()
        }
    }

    @Test fun ratBorrowedAliasesReplacementAndParentClose() = runTest(timeout=120.seconds) {
        val r=Gdal4k.runtime();val d=requireNotNull(r.getDriver("MEM"));val ds=requireNotNull(d.create("",2,2,1,GdalDataType.Byte))
        val b=requireNotNull(ds.getRasterBand(1));val alias=requireNotNull(ds.getRasterBand(1))
        val t=r.createRasterAttributeTable();assertNull(b.getDefaultRAT())
        t.createColumn("value",RasterFieldType.Integer);t.setRowCount(2);t.writeIntegerValues(0,0,intArrayOf(1,2))
        assertEquals(0,b.setDefaultRAT(t));t.setValueAsInt(0,0,99)
        val borrowed=requireNotNull(b.getDefaultRAT());val other=requireNotNull(alias.getDefaultRAT())
        assertEquals(1,borrowed.getValueAsInt(0,0));borrowed.setValueAsInt(1,0,7)
        assertEquals(7,other.getValueAsInt(1,0));val detached=borrowed.cloneTable()
        assertEquals(0,alias.setDefaultRAT(borrowed)) // clone before invalidating the same table
        assertFails { borrowed.getRowCount() };assertFails { other.getRowCount() }
        val after=requireNotNull(b.getDefaultRAT());assertEquals(7,after.getValueAsInt(1,0))
        assertEquals(0,b.setDefaultRAT(null));assertFails { after.getRowCount() };assertNull(b.getDefaultRAT())
        assertEquals(0,b.setDefaultRAT(detached));val finalBorrowed=requireNotNull(b.getDefaultRAT())
        borrowed.close();other.close();ds.close();assertFails { finalBorrowed.getRowCount() };b.close();alias.close();d.close()
        detached.use { assertEquals(7,it.getValueAsInt(1,0)) };t.use { assertEquals(99,it.getValueAsInt(0,0)) }
    }

    @Test fun ratPaletteTranslationAndPersistence() = runTest(timeout=120.seconds) {
        val r=Gdal4k.runtime();r.createColorTable().use { p -> r.createRasterAttributeTable().use { t ->
            p.setColorEntry(0,ColorEntry(10,20,30));p.setColorEntry(1,ColorEntry(40,50,60))
            assertEquals(0,t.initializeFromColorTable(p));assertEquals(2,t.getRowCount())
            requireNotNull(t.translateToColorTable(2)).use { assertEquals(p.getColorEntry(1),it.getColorEntry(1)) }
            assertFails { t.translateToColorTable(0) }
            val path="/vsimem/$datasetOutputPrefix-band-structures.tif"
            requireNotNull(r.getDriver("GTiff")).use { driver ->
                requireNotNull(driver.create(path,2,2,1,GdalDataType.Byte)).use { ds -> requireNotNull(ds.getRasterBand(1)).use { b ->
                    assertEquals(0,b.setColorTable(p));assertEquals(0,b.setDefaultRAT(t))
                } }
                r.openDataset(path).use { ds -> requireNotNull(ds.getRasterBand(1)).use { b ->
                    requireNotNull(b.getColorTable()).use { assertEquals(40,it.getColorEntry(1)?.c1) }
                    requireNotNull(b.getDefaultRAT()).use { assertEquals(2,it.getRowCount()) }
                } }
                assertEquals(0,driver.delete(path))
            }
        } }
    }

    @Test fun nativeBlocksEdgesAndRasterIoCoherence() = runTest(timeout=120.seconds) {
        val r=Gdal4k.runtime();val path="/vsimem/$datasetOutputPrefix-native-blocks.tif"
        requireNotNull(r.getDriver("GTiff")).use { d ->
            requireNotNull(d.create(path,17,19,1,GdalDataType.Byte,arrayOf("TILED=YES","BLOCKXSIZE=16","BLOCKYSIZE=16").toList())).use { ds ->
                requireNotNull(ds.getRasterBand(1)).use { b ->
                    assertContentEquals(intArrayOf(16,16),b.getBlockSize());assertContentEquals(intArrayOf(1,3),b.getActualBlockSize(1,1))
                    assertTrue(b.getSuggestedBlockAccessPattern()>=0)
                    val all=ByteArray(17*19) { (it%101+1).toByte() };assertEquals(0,b.writeRaster(all))
                    val block=ByteArray(259) { -77 };assertEquals(0,b.readBlock(1,1,block))
                    for(y in 0..15)for(x in 0..15)assertEquals(if(y<3&&x==0)all[(16+y)*17+16] else 0.toByte(),block[y*16+x])
                    assertEquals((-77).toByte(),block[258])
                    val cached=ByteArray(all.size);assertEquals(0,b.readRaster(cached))
                    assertEquals(0,b.writeBlock(0,0,ByteArray(256){42}));assertEquals(0,b.readRaster(cached))
                    assertEquals(42.toByte(),cached[0]);assertEquals(42.toByte(),cached[15*17+15]);assertEquals(all[16],cached[16])
                    assertFails { b.readBlock(-1,0,block) };assertFails { b.getActualBlockSize(Int.MAX_VALUE,0) }
                    assertFails { b.writeBlock(0,0,ByteArray(255)) };assertFails { b.readBlock(0,2,block) }
                    assertEquals(0,b.readRaster(cached));assertEquals(42.toByte(),cached[0])
                }
            }
            assertEquals(0,d.delete(path))
        }
    }

    @Test fun nativeBlockBytesPreserveWideAndComplexPixels() = runTest(timeout=120.seconds) {
        val r=Gdal4k.runtime();requireNotNull(r.getDriver("MEM")).use { d ->
            for(type in listOf(GdalDataType.UInt16,GdalDataType.CFloat32)) {
                requireNotNull(d.create("",2,1,1,type)).use { ds -> requireNotNull(ds.getRasterBand(1)).use { b ->
                    val bytes=if(type==GdalDataType.UInt16)4 else 16
                    val payload=ByteArray(bytes){(it*7+1).toByte()};val actual=ByteArray(bytes)
                    assertEquals(0,b.writeBlock(0,0,payload));assertEquals(0,b.readBlock(0,0,actual));assertContentEquals(payload,actual)
                } }
            }
        }
    }
}
