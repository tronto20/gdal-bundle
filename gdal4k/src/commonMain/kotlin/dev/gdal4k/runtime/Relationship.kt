package dev.gdal4k.runtime

/** A GDAL handle. Close with use; children cannot outlive their parent. */
interface Relationship {
    suspend fun close()
    suspend fun getName(): String
    suspend fun getCardinality(): Int
    suspend fun getLeftTableName(): String
    suspend fun getRightTableName(): String
    suspend fun getMappingTableName(): String
    suspend fun setMappingTableName(name: String)
    suspend fun getLeftTableFields(): List<String>
    suspend fun setLeftTableFields(fields: List<String>)
    suspend fun getRightTableFields(): List<String>
    suspend fun setRightTableFields(fields: List<String>)
    suspend fun getType(): Int
    suspend fun setType(type: Int)
}

suspend inline fun <T : Relationship, R> T.use(block: (T) -> R): R {
    try { return block(this) } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { close() }
    }
}
