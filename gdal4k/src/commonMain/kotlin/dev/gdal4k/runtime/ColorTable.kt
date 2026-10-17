package dev.gdal4k.runtime

/** GDAL palette components, in the selected interpretation (RGBA for RGB palettes).
 * Each component fits GDAL's signed 16-bit storage; RGB components normally use 0..255.
 */
data class ColorEntry(val c1: Int, val c2: Int = 0, val c3: Int = 0, val c4: Int = 255) {
    init { require(listOf(c1, c2, c3, c4).all { it in Short.MIN_VALUE..Short.MAX_VALUE }) }
}
object PaletteInterpretation { const val Gray = 0; const val RGB = 1; const val CMYK = 2; const val HLS = 3 }

/** Owned color table. Band.getColorTable returns a detached copy; attach changes explicitly. */
interface ColorTable {
    suspend fun close()
    suspend fun cloneColorTable(): ColorTable
    suspend fun getPaletteInterpretation(): Int
    suspend fun getColorEntryCount(): Int
    suspend fun getColorEntry(index: Int): ColorEntry?
    suspend fun getColorEntryAsRGB(index: Int): ColorEntry?
    suspend fun setColorEntry(index: Int, entry: ColorEntry)
    suspend fun createColorRamp(startIndex: Int, start: ColorEntry, endIndex: Int, end: ColorEntry)
}
suspend inline fun <T : ColorTable, R> T.use(block: (T) -> R): R {
    try { return block(this) } finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { close() } }
}
