package dev.gdal4k.runtime

/**
 * Shared coordinate transformation handle.
 */
interface CoordinateTransformation {
    fun close()

    fun transformPoint(x: Double, y: Double): DoubleArray

    fun transformPoint(x: Double, y: Double, z: Double): DoubleArray

    fun transformPoints(points: Array<DoubleArray>)

    fun transformBounds(
        bounds: DoubleArray,
        xMin: Double,
        yMin: Double,
        xMax: Double,
        yMax: Double,
        densifyPoints: Int = 0,
    )

    fun getInverse(): CoordinateTransformation?
}

inline fun <T : CoordinateTransformation, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
