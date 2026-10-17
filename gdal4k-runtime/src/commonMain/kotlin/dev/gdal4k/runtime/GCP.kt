package dev.gdal4k.runtime

/**
 * Shared GDAL Ground Control Point handle.
 */
interface GCP {
    suspend fun close()

    suspend fun getGCPX(): Double

    suspend fun setGCPX(value: Double)

    suspend fun getGCPY(): Double

    suspend fun setGCPY(value: Double)

    suspend fun getGCPZ(): Double

    suspend fun setGCPZ(value: Double)

    suspend fun getGCPPixel(): Double

    suspend fun setGCPPixel(value: Double)

    suspend fun getGCPLine(): Double

    suspend fun setGCPLine(value: Double)

    suspend fun getInfo(): String

    suspend fun setInfo(info: String)

    suspend fun getId(): String

    suspend fun setId(id: String)
}

suspend inline fun <T : GCP, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { close() }
    }
}
