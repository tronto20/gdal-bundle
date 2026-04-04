package dev.gdal4k.runtime

data class GdalPlatformInfo(
    val classifier: String,
    val osName: String,
    val archName: String,
)

expect object GdalPlatform {
    fun current(): GdalPlatformInfo
}
