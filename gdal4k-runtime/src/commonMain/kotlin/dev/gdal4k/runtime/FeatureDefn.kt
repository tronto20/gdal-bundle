package dev.gdal4k.runtime

/**
 * Shared feature definition handle.
 */
interface FeatureDefn {
    suspend fun close()

    suspend fun getName(): String

    suspend fun getFieldCount(): Int

    suspend fun getFieldDefn(index: Int): FieldDefn?

    suspend fun getFieldIndex(name: String): Int

    suspend fun addFieldDefn(fieldDefn: FieldDefn)

    suspend fun getGeomFieldCount(): Int

    suspend fun getGeomFieldDefn(index: Int): GeomFieldDefn?

    suspend fun getGeomFieldIndex(name: String): Int

    suspend fun addGeomFieldDefn(geomFieldDefn: GeomFieldDefn)

    suspend fun getGeomType(): Int

    suspend fun setGeomType(geomType: Int)

    suspend fun getReferenceCount(): Int

    suspend fun isGeometryIgnored(): Boolean

    suspend fun setGeometryIgnored(ignored: Boolean)

    suspend fun isStyleIgnored(): Boolean

    suspend fun setStyleIgnored(ignored: Boolean)

    suspend fun isSame(other: FeatureDefn): Boolean
}

suspend inline fun <T : FeatureDefn, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { close() }
    }
}
