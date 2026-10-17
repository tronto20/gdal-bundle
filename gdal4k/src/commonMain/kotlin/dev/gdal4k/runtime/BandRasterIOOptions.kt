package dev.gdal4k.runtime

/** A raw single-band window. Zero source sizes use the remaining extent; zero buffer
 * dimensions use the source window size. Scale, offset and NoData replacement are never
 * applied. Reads leave an oversized buffer tail unchanged. Resampling is selected for reads;
 * writes require Nearest, although GDAL may resize the buffer to the destination window.
 */
data class BandRasterIOOptions(
    val xOffset: Int = 0,
    val yOffset: Int = 0,
    val xSize: Int = 0,
    val ySize: Int = 0,
    val bufferWidth: Int = 0,
    val bufferHeight: Int = 0,
    val resampling: RasterResampling = RasterResampling.Nearest,
)

/** Statistics are over raw valid samples; availability and persistence depend on the driver. */
data class RasterStatistics(val minimum: Double, val maximum: Double, val mean: Double, val standardDeviation: Double)

/** Detached histogram snapshot. Counts use signed Long: raster dimensions bound the pixel
 * count below Long.MAX_VALUE. Setters reject negative counts. Mutating this array changes
 * only this snapshot, never the dataset's native cached histogram.
 */
data class RasterHistogram(val minimum: Double, val maximum: Double, val counts: LongArray)
