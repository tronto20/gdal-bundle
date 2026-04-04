package dev.gdal4k.runtime

/**
 * Shared feature definition handle.
 */
interface FeatureDefn {
    fun close()

    fun getName(): String

    fun getFieldCount(): Int

    fun getFieldDefn(index: Int): FieldDefn?

    fun getFieldIndex(name: String): Int

    fun addFieldDefn(fieldDefn: FieldDefn)

    fun getGeomFieldCount(): Int

    fun getGeomFieldDefn(index: Int): GeomFieldDefn?

    fun getGeomFieldIndex(name: String): Int

    fun addGeomFieldDefn(geomFieldDefn: GeomFieldDefn)

    fun getGeomType(): Int

    fun setGeomType(geomType: Int)

    fun getReferenceCount(): Int

    fun isGeometryIgnored(): Boolean

    fun setGeometryIgnored(ignored: Boolean)

    fun isStyleIgnored(): Boolean

    fun setStyleIgnored(ignored: Boolean)

    fun isSame(other: FeatureDefn): Boolean
}

inline fun <T : FeatureDefn, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
