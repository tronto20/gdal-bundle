package dev.gdal4k.runtime

/** A Dataset RasterIO window. Zero sizes use the remaining source extent; zero buffer sizes
 * use the source window size. Bands are 1-based; empty selects all bands.
 * Buffers are contiguous, band-sequential: all pixels of the first selected band, then the next.
 * Reads leave an oversized buffer's tail unchanged. Resampling applies to reads.
 */
data class RasterIOOptions(
    val xOffset: Int = 0,
    val yOffset: Int = 0,
    val xSize: Int = 0,
    val ySize: Int = 0,
    val bufferWidth: Int = 0,
    val bufferHeight: Int = 0,
    val bands: IntArray = intArrayOf(),
    val resampling: RasterResampling = RasterResampling.Nearest,
)

enum class RasterResampling(val code: Int) {
    Nearest(0), Bilinear(1), Cubic(2), CubicSpline(3), Lanczos(4), Average(5), Mode(6), Gauss(7), Rms(14),
}
