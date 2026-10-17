package dev.gdal4k.runtime

/**
 * Shared raster band handle.
 *
 * Provides band metadata, overview/mask access, and full-band or windowed raster I/O.
 */
interface Band {
    suspend fun close()

    suspend fun getXSize(): Int

    suspend fun getYSize(): Int

    suspend fun getRasterDataType(): Int

    suspend fun getBlockSize(): IntArray

    suspend fun checksum(): Int

    suspend fun getColorInterpretation(): Int

    suspend fun setColorInterpretation(colorInterpretation: Int): Int

    suspend fun getNoDataValue(): Double?

    suspend fun setNoDataValue(value: Double): Int

    suspend fun deleteNoDataValue(): Int

    suspend fun getUnitType(): String

    suspend fun setUnitType(unitType: String): Int

    suspend fun getCategoryNames(): List<String>

    suspend fun setCategoryNames(names: List<String>): Int

    suspend fun getOverviewCount(): Int

    suspend fun getOverview(index: Int): Band?

    suspend fun getSampleOverview(sampleSize: Long): Band?

    suspend fun getMaskBand(): Band?

    suspend fun getMaskFlags(): Int

    suspend fun createMaskBand(flags: Int): Int

    suspend fun isMaskBand(): Boolean

    suspend fun flushCache()

    suspend fun readRaster(buffer: ByteArray): Int

    suspend fun readRaster(buffer: ShortArray): Int

    suspend fun readRaster(buffer: IntArray): Int

    suspend fun readRaster(buffer: LongArray): Int

    suspend fun readRaster(buffer: FloatArray): Int

    suspend fun readRaster(buffer: DoubleArray): Int

    suspend fun readRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: ByteArray,
    ): Int

    suspend fun readRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: ShortArray,
    ): Int

    suspend fun readRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: IntArray,
    ): Int

    suspend fun readRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: LongArray,
    ): Int

    suspend fun readRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: FloatArray,
    ): Int

    suspend fun readRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: DoubleArray,
    ): Int

    suspend fun writeRaster(buffer: ByteArray): Int

    suspend fun writeRaster(buffer: ShortArray): Int

    suspend fun writeRaster(buffer: IntArray): Int

    suspend fun writeRaster(buffer: LongArray): Int

    suspend fun writeRaster(buffer: FloatArray): Int

    suspend fun writeRaster(buffer: DoubleArray): Int

    suspend fun writeRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: ByteArray,
    ): Int

    suspend fun writeRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: ShortArray,
    ): Int

    suspend fun writeRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: IntArray,
    ): Int

    suspend fun writeRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: LongArray,
    ): Int

    suspend fun writeRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: FloatArray,
    ): Int

    suspend fun writeRaster(
        xOffset: Int,
        yOffset: Int,
        xSize: Int,
        ySize: Int,
        buffer: DoubleArray,
    ): Int
}

suspend inline fun <T : Band, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { close() }
    }
}
