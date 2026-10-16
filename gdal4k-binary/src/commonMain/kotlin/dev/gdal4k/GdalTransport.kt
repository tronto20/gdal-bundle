package dev.gdal4k

internal data class GdalOptions(
    val libraryPath: String? = null,
    val projData: String? = null,
    val gdalData: String? = null,
    val webBridgeUrl: String? = null,
)

internal interface GdalTransport {
    suspend fun call(request: String): String
    suspend fun close()
}
internal expect suspend fun createTransport(options: GdalOptions): GdalTransport
