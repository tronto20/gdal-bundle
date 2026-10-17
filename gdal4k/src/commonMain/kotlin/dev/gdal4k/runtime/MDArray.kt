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
     * Values outside Float64 precision require a future typed-array overload.
     */
    suspend fun read(start: LongArray, count: LongArray): DoubleArray
    suspend fun write(start: LongArray, count: LongArray, values: DoubleArray): Boolean
}

suspend inline fun <T : MDArray, R> T.use(block: (T) -> R): R {
    try { return block(this) } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { close() }
    }
}
