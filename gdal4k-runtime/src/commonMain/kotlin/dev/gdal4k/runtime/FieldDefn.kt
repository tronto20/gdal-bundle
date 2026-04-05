package dev.gdal4k.runtime

/**
 * Shared OGR field definition handle.
 */
interface FieldDefn {
    fun close()

    fun getName(): String

    fun getNameRef(): String

    fun setName(name: String)

    fun getAlternativeName(): String

    fun getAlternativeNameRef(): String

    fun setAlternativeName(name: String)

    fun getType(): Int

    fun setType(type: Int)

    fun getFieldType(): Int

    fun getSubType(): Int

    fun setSubType(subType: Int)

    fun getJustify(): Int

    fun setJustify(justify: Int)

    fun getWidth(): Int

    fun setWidth(width: Int)

    fun getPrecision(): Int

    fun setPrecision(precision: Int)

    fun getTZFlag(): Int

    fun setTZFlag(flag: Int)

    fun getTypeName(): String

    fun getFieldTypeName(type: Int): String

    fun isIgnored(): Boolean

    fun setIgnored(ignored: Boolean)

    fun isNullable(): Boolean

    fun setNullable(nullable: Boolean)

    fun isUnique(): Boolean

    fun setUnique(unique: Boolean)

    fun isGenerated(): Boolean

    fun setGenerated(generated: Boolean)

    fun getDefault(): String

    fun setDefault(defaultValue: String)

    fun isDefaultDriverSpecific(): Boolean

    fun getDomainName(): String

    fun getDomainNameRef(): String

    fun setDomainName(domainName: String)

    fun getComment(): String

    fun getCommentRef(): String

    fun setComment(comment: String)
}

inline fun <T : FieldDefn, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
