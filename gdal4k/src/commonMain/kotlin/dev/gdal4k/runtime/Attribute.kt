package dev.gdal4k.runtime

/** Owned attribute reference; closing its group/array also closes this handle. */
interface Attribute {
    suspend fun close()
    suspend fun getName(): String
    suspend fun getFullName(): String
    suspend fun getDimensions(): LongArray
    suspend fun getDataType(): MDDataType
    /** Attribute values are small metadata; reads/writes cover the whole attribute. */
    suspend fun readValues(): List<MDValue>
    suspend fun writeValues(values: List<MDValue>): Boolean
}

suspend inline fun <T : Attribute, R> T.use(block: (T) -> R): R {
    try { return block(this) } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { close() }
    }
}
