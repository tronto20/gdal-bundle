package dev.gdal4k.probe

object NativeProbe {
    init {
        val path = System.getProperty("gdal4k.probe.library")
        if (path == null) System.loadLibrary("gdal4k_probe") else System.load(path)
    }
    @JvmStatic external fun execute(): Int
}

actual suspend fun runGdalProbe(): Int = NativeProbe.execute()
