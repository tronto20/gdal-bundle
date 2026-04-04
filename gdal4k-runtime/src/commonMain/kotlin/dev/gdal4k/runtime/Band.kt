package dev.gdal4k.runtime

/**
 * Shared raster band handle.
 *
 * The first version focuses on band metadata, overview/mask access, and full-band raster I/O.
 * Windowed and more advanced raster operations can be added as the surface grows.
 */
interface Band {
    fun close()

    fun getXSize(): Int

    fun getYSize(): Int

    fun getRasterDataType(): Int

    fun getBlockSize(): IntArray

    fun checksum(): Int

    fun getColorInterpretation(): Int

    fun setColorInterpretation(colorInterpretation: Int): Int

    fun getNoDataValue(): Double?

    fun setNoDataValue(value: Double): Int

    fun deleteNoDataValue(): Int

    fun getUnitType(): String

    fun setUnitType(unitType: String): Int

    fun getCategoryNames(): List<String>

    fun setCategoryNames(names: List<String>): Int

    fun getOverviewCount(): Int

    fun getOverview(index: Int): Band?

    fun getSampleOverview(sampleSize: Long): Band?

    fun getMaskBand(): Band?

    fun getMaskFlags(): Int

    fun createMaskBand(flags: Int): Int

    fun isMaskBand(): Boolean

    fun flushCache()

    fun readRaster(buffer: ByteArray): Int

    fun readRaster(buffer: ShortArray): Int

    fun readRaster(buffer: IntArray): Int

    fun readRaster(buffer: LongArray): Int

    fun readRaster(buffer: FloatArray): Int

    fun readRaster(buffer: DoubleArray): Int

    fun readRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: ByteArray,
    ): Int

    fun readRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: ShortArray,
    ): Int

    fun readRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: IntArray,
    ): Int

    fun readRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: LongArray,
    ): Int

    fun readRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: FloatArray,
    ): Int

    fun readRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: DoubleArray,
    ): Int

    fun writeRaster(buffer: ByteArray): Int

    fun writeRaster(buffer: ShortArray): Int

    fun writeRaster(buffer: IntArray): Int

    fun writeRaster(buffer: LongArray): Int

    fun writeRaster(buffer: FloatArray): Int

    fun writeRaster(buffer: DoubleArray): Int

    fun writeRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: ByteArray,
    ): Int

    fun writeRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: ShortArray,
    ): Int

    fun writeRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: IntArray,
    ): Int

    fun writeRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: LongArray,
    ): Int

    fun writeRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: FloatArray,
    ): Int

    fun writeRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: DoubleArray,
    ): Int
}

inline fun <T : Band, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
