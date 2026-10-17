package dev.gdal4k.runtime

/**
 * Shared OGR geometry field definition handle.
 */
interface GeomFieldDefn {
    suspend fun close()

    suspend fun getName(): String

    suspend fun getNameRef(): String

    suspend fun setName(name: String)

    suspend fun getType(): Int

    suspend fun setType(type: Int)

    suspend fun isIgnored(): Boolean

    suspend fun setIgnored(ignored: Boolean)

    suspend fun isNullable(): Boolean

    suspend fun setNullable(nullable: Boolean)
}

suspend inline fun <T : GeomFieldDefn, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { close() }
    }
}
