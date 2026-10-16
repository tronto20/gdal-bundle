package dev.gdal4k.runtime

/**
 * Shared coordinate transformation handle.
 */
interface CoordinateTransformation {
    suspend fun close()

    suspend fun transformPoint(x: Double, y: Double): DoubleArray

    suspend fun transformPoint(x: Double, y: Double, z: Double): DoubleArray

    suspend fun transformPoints(points: Array<DoubleArray>)

    suspend fun transformBounds(
        bounds: DoubleArray,
        xMin: Double,
        yMin: Double,
        xMax: Double,
        yMax: Double,
        densifyPoints: Int = 0,
    )

    suspend fun getInverse(): CoordinateTransformation?
}

suspend inline fun <T : CoordinateTransformation, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { close() }
    }
}
