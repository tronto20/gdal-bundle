package dev.gdal4k.runtime

/** A GDAL handle. Close with use; children cannot outlive their parent. */
interface MDArray {
    suspend fun close()
    suspend fun getName(): String
    suspend fun getFullName(): String
    suspend fun getDimensions(): List<Dimension>
    /** GDAL numeric type; non-numeric arrays return GdalDataType.Unknown. */
    suspend fun getDataType(): Int
    /** Numeric slices are converted to Float64, in row-major dimension order.
     * Pass one start and count per dimension; scalar arrays use empty arrays.
     * Use readValues/writeValues to preserve integer precision or complex values.
     */
    suspend fun read(start: LongArray, count: LongArray): DoubleArray
    suspend fun write(start: LongArray, count: LongArray, values: DoubleArray): Boolean
    suspend fun getAttribute(name: String): Attribute?
    suspend fun getAttributes(options: List<String> = emptyList()): List<Attribute>
    /** Empty dimensions create a scalar attribute. */
    suspend fun createAttribute(name: String, dimensions: LongArray, dataType: MDDataType, options: List<String> = emptyList()): Attribute?
    suspend fun deleteAttribute(name: String, options: List<String> = emptyList()): Boolean
    suspend fun getExtendedDataType(): MDDataType
    /** Reads only the requested window, in row-major order, preserving the stored type. */
    suspend fun readValues(start: LongArray, count: LongArray): List<MDValue>
    suspend fun writeValues(start: LongArray, count: LongArray, values: List<MDValue>): Boolean

}

suspend inline fun <T : MDArray, R> T.use(block: (T) -> R): R {
    try { return block(this) } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { close() }
    }
}
