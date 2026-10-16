package dev.gdal4k

import dev.gdal4k.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class LifecycleTest {
    @Test fun cancellationAndShutdown() = runTest(timeout = 180.seconds) {
        val cancelledPreparation = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            assertFailsWith<CancellationException> { Gdal4k.prepare() }
        }
        cancelledPreparation.join()
        Gdal4k.prepare()
        val runtime = Gdal4k.runtime()
        try {
            val memory = requireNotNull(runtime.getDriver("MEM"))
            val source = requireNotNull(memory.create("", 2048, 2048, 1, GdalDataType.Byte))
            val jxl = requireNotNull(runtime.getDriver("JPEGXL"))
            var completed: Dataset? = null
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                completed = jxl.createCopy("/vsimem/gdal4k-cancellation/image.jxl", source, options = listOf("LOSSLESS=YES", "EFFORT=7"))
            }
            // This call has already entered the engine before cancellation. Native code must finish safely.
            job.cancelAndJoin()
            requireNotNull(completed).use { assertEquals(2048, it.getRasterXSize()) }
            runtime.deleteMemoryFile("/vsimem/gdal4k-cancellation")
            source.close()
            val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                assertFailsWith<CancellationException> { runtime.getDriver("MEM") }
            }
            cancelled.join()
            runtime.createSpatialReference("EPSG:4326").use { assertTrue(it.isGeographic()) }
        } finally { Gdal4k.close() }
        assertFails { runtime.versionInfo() }
        Gdal4k.close() // idempotent
        Gdal4k.prepare()
        try {
            val restarted = Gdal4k.runtime()
            assertEquals("3120400", restarted.versionInfo("VERSION_NUM"))
            val source = requireNotNull(requireNotNull(restarted.getDriver("MEM")).create("", 2048, 2048, 1, GdalDataType.Byte))
            val driver = requireNotNull(restarted.getDriver("JPEGXL"))
            val active = async(start = CoroutineStart.UNDISPATCHED) {
                driver.createCopy("/vsimem/gdal4k-shutdown.jxl", source, options = listOf("LOSSLESS=YES", "EFFORT=7"))
            }
            val closing = async(start = CoroutineStart.UNDISPATCHED) { Gdal4k.close() }
            requireNotNull(active.await()).close()
            closing.await()
            assertFails { restarted.versionInfo() }
        }
        finally { Gdal4k.close() }
        println("GDAL4K_CANCELLATION_AND_SHUTDOWN_OK")
    }
}
