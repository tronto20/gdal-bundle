package dev.gdal4k.runtime

/** A GDAL handle. Close with use; children cannot outlive their parent. */
interface Dimension {
    suspend fun close()
    suspend fun getName(): String
    suspend fun getFullName(): String
    suspend fun getType(): String
    suspend fun getDirection(): String
    suspend fun getSize(): Long
}

suspend inline fun <T : Dimension, R> T.use(block: (T) -> R): R {
    try { return block(this) } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { close() }
    }
}
