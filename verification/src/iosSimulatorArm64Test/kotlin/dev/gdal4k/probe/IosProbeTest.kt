package dev.gdal4k.probe

import kotlin.test.Test

class IosProbeTest {
    @Test
    fun executeGdalOnSimulator() {
        // Native's actual implementation completes synchronously. A GDAL failure
        // is rethrown by the shared entry point and fails this simulator test.
        main()
    }
}
