package dev.gdal4k.runtime

/**
 * Shared OGR feature handle.
 */
interface Feature {
    suspend fun close()

    suspend fun getDefinition(): FeatureDefn

    suspend fun cloneFeature(): Feature

    suspend fun equal(other: Feature): Boolean

    suspend fun getFID(): Long

    suspend fun setFID(fid: Long): Int

    suspend fun getGeometryRef(): Geometry?

    suspend fun setGeometry(geometry: Geometry): Int

    suspend fun setGeometryDirectly(geometry: Geometry): Int

    suspend fun getFieldCount(): Int

    suspend fun getFieldDefn(index: Int): FieldDefn?

    suspend fun getFieldDefn(name: String): FieldDefn?

    suspend fun getGeomFieldCount(): Int

    suspend fun getGeomFieldDefn(index: Int): GeomFieldDefn?

    suspend fun getGeomFieldDefn(name: String): GeomFieldDefn?

    suspend fun getGeomFieldIndex(name: String): Int

    suspend fun getGeomFieldRef(index: Int): Geometry?

    suspend fun getGeomFieldRef(name: String): Geometry?

    suspend fun setGeomField(index: Int, geometry: Geometry): Int

    suspend fun setGeomField(name: String, geometry: Geometry): Int

    suspend fun setGeomFieldDirectly(index: Int, geometry: Geometry): Int

    suspend fun setGeomFieldDirectly(name: String, geometry: Geometry): Int

    suspend fun getFieldAsString(index: Int): String

    suspend fun getFieldAsString(name: String): String

    suspend fun getFieldAsInteger(index: Int): Int

    suspend fun getFieldAsInteger(name: String): Int

    suspend fun getFieldAsInteger64(index: Int): Long

    suspend fun getFieldAsInteger64(name: String): Long

    suspend fun getFieldAsDouble(index: Int): Double

    suspend fun getFieldAsDouble(name: String): Double

    suspend fun getFieldAsStringList(index: Int): List<String>

    suspend fun getFieldAsIntegerList(index: Int): IntArray

    suspend fun getFieldAsDoubleList(index: Int): DoubleArray

    suspend fun getFieldAsBinary(index: Int): ByteArray

    suspend fun isFieldSet(index: Int): Boolean

    suspend fun isFieldSet(name: String): Boolean

    suspend fun isFieldNull(index: Int): Boolean

    suspend fun isFieldNull(name: String): Boolean

    suspend fun isFieldSetAndNotNull(index: Int): Boolean

    suspend fun isFieldSetAndNotNull(name: String): Boolean

    suspend fun getFieldIndex(name: String): Int

    suspend fun dumpReadableAsString(): String

    suspend fun getStyleString(): String

    suspend fun setStyleString(style: String)

    suspend fun getFieldType(index: Int): Int

    suspend fun getFieldType(name: String): Int

    suspend fun validate(flags: Int = 0, options: Int = 0): Int

    suspend fun fillUnsetWithDefault()

    suspend fun getNativeData(): String

    suspend fun getNativeMediaType(): String

    suspend fun setNativeData(nativeData: String)

    suspend fun setNativeMediaType(nativeMediaType: String)

    suspend fun unsetField(index: Int)

    suspend fun unsetField(name: String)

    suspend fun setFieldNull(index: Int)

    suspend fun setFieldNull(name: String)

    suspend fun setField(index: Int, value: String)

    suspend fun setField(name: String, value: String)

    suspend fun setField(index: Int, value: Int)

    suspend fun setField(name: String, value: Int)

    suspend fun setFieldInteger64(index: Int, value: Long)

    suspend fun setField(index: Int, value: Double)

    suspend fun setField(name: String, value: Double)
}

suspend inline fun <T : Feature, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { close() }
    }
}
