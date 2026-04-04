package dev.gdal4k.runtime

/**
 * Shared OGR feature handle.
 */
interface Feature {
    fun close()

    fun getDefinition(): FeatureDefn

    fun cloneFeature(): Feature

    fun equal(other: Feature): Boolean

    fun getFID(): Long

    fun setFID(fid: Long): Int

    fun getGeometryRef(): Geometry?

    fun setGeometry(geometry: Geometry): Int

    fun setGeometryDirectly(geometry: Geometry): Int

    fun getFieldCount(): Int

    fun getFieldDefn(index: Int): FieldDefn?

    fun getFieldDefn(name: String): FieldDefn?

    fun getGeomFieldCount(): Int

    fun getGeomFieldDefn(index: Int): GeomFieldDefn?

    fun getGeomFieldDefn(name: String): GeomFieldDefn?

    fun getGeomFieldIndex(name: String): Int

    fun getGeomFieldRef(index: Int): Geometry?

    fun getGeomFieldRef(name: String): Geometry?

    fun setGeomField(index: Int, geometry: Geometry): Int

    fun setGeomField(name: String, geometry: Geometry): Int

    fun setGeomFieldDirectly(index: Int, geometry: Geometry): Int

    fun setGeomFieldDirectly(name: String, geometry: Geometry): Int

    fun getFieldAsString(index: Int): String

    fun getFieldAsString(name: String): String

    fun getFieldAsInteger(index: Int): Int

    fun getFieldAsInteger(name: String): Int

    fun getFieldAsInteger64(index: Int): Long

    fun getFieldAsInteger64(name: String): Long

    fun getFieldAsDouble(index: Int): Double

    fun getFieldAsDouble(name: String): Double

    fun getFieldAsStringList(index: Int): List<String>

    fun getFieldAsIntegerList(index: Int): IntArray

    fun getFieldAsDoubleList(index: Int): DoubleArray

    fun getFieldAsBinary(index: Int): ByteArray

    fun isFieldSet(index: Int): Boolean

    fun isFieldSet(name: String): Boolean

    fun isFieldNull(index: Int): Boolean

    fun isFieldNull(name: String): Boolean

    fun isFieldSetAndNotNull(index: Int): Boolean

    fun isFieldSetAndNotNull(name: String): Boolean

    fun getFieldIndex(name: String): Int

    fun dumpReadableAsString(): String

    fun getStyleString(): String

    fun setStyleString(style: String)

    fun getFieldType(index: Int): Int

    fun getFieldType(name: String): Int

    fun validate(flags: Int = 0, options: Int = 0): Int

    fun fillUnsetWithDefault()

    fun getNativeData(): String

    fun getNativeMediaType(): String

    fun setNativeData(nativeData: String)

    fun setNativeMediaType(nativeMediaType: String)

    fun unsetField(index: Int)

    fun unsetField(name: String)

    fun setFieldNull(index: Int)

    fun setFieldNull(name: String)

    fun setField(index: Int, value: String)

    fun setField(name: String, value: String)

    fun setField(index: Int, value: Int)

    fun setField(name: String, value: Int)

    fun setFieldInteger64(index: Int, value: Long)

    fun setField(index: Int, value: Double)

    fun setField(name: String, value: Double)
}

inline fun <T : Feature, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
