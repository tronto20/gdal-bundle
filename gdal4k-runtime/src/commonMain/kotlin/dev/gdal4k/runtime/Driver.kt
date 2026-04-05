package dev.gdal4k.runtime

/**
 * Shared GDAL driver handle.
 */
interface Driver {
    fun close()

    fun getShortName(): String

    fun getLongName(): String

    fun getHelpTopic(): String

    fun create(
        name: String,
        xSize: Int,
        ySize: Int,
        bandCount: Int = 1,
        dataType: Int = 0,
        options: List<String> = emptyList(),
    ): Dataset?

    fun createCopy(
        name: String,
        dataset: Dataset,
        strict: Int = 0,
        options: List<String> = emptyList(),
    ): Dataset?

    fun createVector(
        name: String,
        options: List<String> = emptyList(),
    ): Dataset?

    fun delete(name: String): Int

    fun rename(from: String, to: String): Int

    fun copyFiles(from: String, to: String): Int

    fun hasOpenOption(option: String): Boolean

    fun register(): Int

    fun deregister()
}

inline fun <T : Driver, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
