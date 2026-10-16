package dev.gdal4k

import dev.gdal4k.binary.Gdal4kBinary
import dev.gdal4k.runtime.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class BundledRuntimeTest {
    @Test fun originalInterface() = runTest(timeout = 120.seconds) {
        Gdal4kBinary.prepare()
        val runtime = Gdal4kBinary.runtime()
        assertEquals("3120400", runtime.versionInfo("VERSION_NUM"))
        assertTrue(SurfaceSmokeService(runtime).run().contains("surface smoke passed"))
        // UInt8 bytes must survive signed Kotlin Byte values, including 255.
        val name = "/vsimem/영상-🛰-bytes"
        val bytes = byteArrayOf(0, 127, -128, -1)
        runtime.writeMemoryFile(name, bytes)
        assertContentEquals(bytes, runtime.readMemoryFile(name))
        runtime.deleteMemoryFile(name)
        assertFails { runtime.readMemoryFile(name) }
        val driver = requireNotNull(runtime.getDriver("MEM"))
        val dataset = requireNotNull(driver.create("", 2, 2, 1, GdalDataType.Int64)) // Int64
        val band = requireNotNull(dataset.getRasterBand(1))
        val integers = longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE, 9007199254740993L, -9007199254740993L)
        assertEquals(0, band.writeRaster(integers))
        val read = LongArray(4)
        assertEquals(0, band.readRaster(read))
        assertContentEquals(integers, read)
        dataset.close()
        assertFails { band.getXSize() }
        band.close(); band.close(); dataset.close()
        // A failed CRS must not poison a subsequent request.
        assertFails { runtime.createSpatialReference("EPSG:NOT_A_CRS") }
        runtime.createSpatialReference("EPSG:4326").use { assertTrue(it.isGeographic()) }
        // Replacing/emptying native geometry must invalidate borrowed aliases, not leave dangling pointers.
        val vector = "/vsimem/gdal4k-borrowed.geojson"
        runtime.writeMemoryFile(vector, """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"GeometryCollection","geometries":[{"type":"Point","coordinates":[0,0]},{"type":"Point","coordinates":[1,1]}]}}]}""".encodeToByteArray())
        runtime.openDataset(vector).use { collection ->
            val layer = requireNotNull(collection.getLayer(0))
            requireNotNull(layer.getNextFeature()).use { feature ->
                val borrowed = requireNotNull(feature.getGeometryRef())
                borrowed.cloneGeometry().use { replacement -> assertEquals(0, feature.setGeometry(replacement)) }
                assertFails { borrowed.getGeometryCount() }
                borrowed.close()
                val geometry = requireNotNull(feature.getGeometryRef())
                val alias = requireNotNull(feature.getGeometryRef())
                val child = requireNotNull(alias.getGeometryRef(0))
                geometry.empty()
                assertFails { child.exportToWkt() }
                child.close()
                assertEquals(0, geometry.getGeometryCount())
            }
        }
        runtime.deleteMemoryFile(vector)
        println("GDAL4K_BUNDLED_ORIGINAL_API_OK")
    }
}
