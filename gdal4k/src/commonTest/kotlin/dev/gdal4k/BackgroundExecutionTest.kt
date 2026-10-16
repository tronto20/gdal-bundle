package dev.gdal4k

import dev.gdal4k.binary.Gdal4kBinary
import dev.gdal4k.runtime.use
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

internal expect suspend fun verifyBackground(block: suspend () -> Unit)

class BackgroundExecutionTest {
    @Test fun jpegXlDoesNotBlockCaller() = runTest(timeout = 180.seconds) {
        Gdal4kBinary.prepare()
        val runtime = Gdal4kBinary.runtime()
        requireNotNull(runtime.getDriver("MEM")).use { memory ->
            requireNotNull(memory.create("", 3072, 3072, 1, 1)).use { source ->
                requireNotNull(runtime.getDriver("JPEGXL")).use { jxl ->
                    verifyBackground {
                        requireNotNull(jxl.createCopy("/vsimem/gdal4k-background.jxl", source, options = listOf("LOSSLESS=YES", "EFFORT=7"))).use {
                            check(it.getRasterXSize() == 3072)
                        }
                    }
                }
            }
        }
        runtime.deleteMemoryFile("/vsimem/gdal4k-background.jxl")
        println("GDAL4K_BACKGROUND_EXECUTION_OK")
    }
}
