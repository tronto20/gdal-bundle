package dev.gdal4k.runtime

object RasterFieldType { const val Integer = 0; const val Real = 1; const val String = 2 }
object RasterFieldUsage {
    const val Generic = 0; const val PixelCount = 1; const val Name = 2; const val Minimum = 3
    const val Maximum = 4; const val MinMax = 5; const val Red = 6; const val Green = 7
    const val Blue = 8; const val Alpha = 9; const val RedMinimum = 10; const val GreenMinimum = 11
    const val BlueMinimum = 12; const val AlphaMinimum = 13; const val RedMaximum = 14
    const val GreenMaximum = 15; const val BlueMaximum = 16; const val AlphaMaximum = 17
}
object RasterTableType { const val Thematic = 0; const val Athematic = 1 }

/** RAT handle with bounded column windows. A band's table is borrowed and invalidated
 * when replaced or its parent closes. cloneTable makes an independent owned copy.
 * Mutations follow the driver: check changesAreWrittenToFile; otherwise reattach with
 * Band.setDefaultRAT to persist them. This surface supports integer/real/string columns;
 * Value methods require a matching column type, avoiding implicit numeric/string coercion.
 * GDAL 3.12 Boolean/DateTime/WKB typed access is not exposed yet.
 */
interface RasterAttributeTable {
    suspend fun close()
    suspend fun cloneTable(): RasterAttributeTable
    suspend fun getColumnCount(): Int
    suspend fun getRowCount(): Int
    suspend fun setRowCount(count: Int)
    suspend fun getNameOfCol(column: Int): String
    suspend fun getTypeOfCol(column: Int): Int
    suspend fun getUsageOfCol(column: Int): Int
    suspend fun getColOfUsage(usage: Int): Int
    suspend fun createColumn(name: String, type: Int, usage: Int = 0): Int
    suspend fun getValueAsInt(row: Int, column: Int): Int
    suspend fun getValueAsDouble(row: Int, column: Int): Double
    suspend fun getValueAsString(row: Int, column: Int): String
    suspend fun setValueAsInt(row: Int, column: Int, value: Int)
    suspend fun setValueAsDouble(row: Int, column: Int, value: Double)
    suspend fun setValueAsString(row: Int, column: Int, value: String)
    suspend fun readIntegerValues(column: Int, startRow: Int, count: Int): IntArray
    suspend fun writeIntegerValues(column: Int, startRow: Int, values: IntArray): Int
    suspend fun readDoubleValues(column: Int, startRow: Int, count: Int): DoubleArray
    suspend fun writeDoubleValues(column: Int, startRow: Int, values: DoubleArray): Int
    suspend fun readStringValues(column: Int, startRow: Int, count: Int): List<String>
    suspend fun writeStringValues(column: Int, startRow: Int, values: List<String>)
    suspend fun getLinearBinning(): DoubleArray?
    suspend fun setLinearBinning(rowZeroMinimum: Double, binSize: Double): Int
    /** Finite value only; returns -1 when no row matches, including values beyond linear bins. */
    suspend fun getRowOfValue(value: Double): Int
    suspend fun getTableType(): Int
    suspend fun setTableType(type: Int): Int
    suspend fun changesAreWrittenToFile(): Boolean
    suspend fun initializeFromColorTable(table: ColorTable): Int
    suspend fun translateToColorTable(entryCount: Int = -1): ColorTable?
    suspend fun removeStatistics()
}
suspend inline fun <T : RasterAttributeTable, R> T.use(block: (T) -> R): R {
    try { return block(this) } finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { close() } }
}
