package dev.gdal4k
internal actual fun loadBundledLibrary(path: String?) {
    check(android.os.Process.is64Bit() && "arm64-v8a" in android.os.Build.SUPPORTED_64_BIT_ABIS) {
        "gdal4k supports only Android ARM64 (arm64-v8a). " +
            "Android x86_64 and 32-bit engines are not provided. " +
            "Supported device ABIs: ${android.os.Build.SUPPORTED_ABIS.joinToString()}"
    }
    if (path == null) System.loadLibrary("gdal4k") else System.load(path)
}
