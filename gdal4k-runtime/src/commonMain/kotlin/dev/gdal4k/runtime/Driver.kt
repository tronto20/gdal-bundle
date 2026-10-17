package dev.gdal4k.runtime

/**
 * Shared GDAL driver handle.
 */
interface Driver {
    suspend fun close()

    suspend fun getShortName(): String

    suspend fun getLongName(): String

    suspend fun getHelpTopic(): String

    suspend fun create(
        name: String,
        xSize: Int,
        ySize: Int,
        bandCount: Int = 1,
        dataType: Int = 0,
        options: List<String> = emptyList(),
    ): Dataset?

    suspend fun createCopy(
        name: String,
        dataset: Dataset,
        strict: Int = 0,
        options: List<String> = emptyList(),
    ): Dataset?

    suspend fun createVector(
        name: String,
        options: List<String> = emptyList(),
    ): Dataset?

    suspend fun delete(name: String): Int

    suspend fun rename(from: String, to: String): Int

    suspend fun copyFiles(from: String, to: String): Int

    suspend fun hasOpenOption(option: String): Boolean

    suspend fun register(): Int

    suspend fun deregister()
}

suspend inline fun <T : Driver, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { close() }
    }
}
