package dev.gdal4k.runtime

/**
 * Shared OGR geometry field definition handle.
 */
interface GeomFieldDefn {
    fun close()

    fun getName(): String

    fun getNameRef(): String

    fun setName(name: String)

    fun getType(): Int

    fun setType(type: Int)

    fun isIgnored(): Boolean

    fun setIgnored(ignored: Boolean)

    fun isNullable(): Boolean

    fun setNullable(nullable: Boolean)
}

inline fun <T : GeomFieldDefn, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
