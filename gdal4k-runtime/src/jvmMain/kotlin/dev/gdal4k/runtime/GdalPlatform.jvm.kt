package dev.gdal4k.runtime

import java.util.Locale

actual object GdalPlatform {
    actual fun current(): GdalPlatformInfo {
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val arch = normalizeArch(System.getProperty("os.arch"))

        return when {
            os.contains("mac") && arch == "arm64" ->
                GdalPlatformInfo("macos-arm64", "macos", "arm64")

            os.contains("linux") && arch == "amd64" ->
                GdalPlatformInfo("linux-amd64", "linux", "amd64")

            os.contains("linux") && arch == "arm64" ->
                GdalPlatformInfo("linux-arm64", "linux", "arm64")

            os.contains("win") && arch == "amd64" ->
                GdalPlatformInfo("windows-amd64", "windows", "amd64")

            os.contains("win") && arch == "arm64" ->
                GdalPlatformInfo("windows-arm64", "windows", "arm64")

            else -> error(
                "Unsupported OS/arch: ${System.getProperty("os.name")} / ${System.getProperty("os.arch")}. " +
                    "Supported classifiers are macos-arm64, linux-amd64, linux-arm64, windows-amd64, windows-arm64.",
            )
        }
    }

    private fun normalizeArch(raw: String): String {
        return when (raw.lowercase(Locale.ROOT)) {
            "x86_64", "amd64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> raw.lowercase(Locale.ROOT)
        }
    }
}
