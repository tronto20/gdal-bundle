package dev.gdal4k.runtime

/** A GDAL handle. Close with use; children cannot outlive their parent. */
interface FieldDomain {
    suspend fun close()
    suspend fun getName(): String
    suspend fun getDescription(): String
    suspend fun getDomainType(): Int
    suspend fun getFieldType(): Int
    suspend fun getFieldSubType(): Int
    /** Null for non-coded domains. */
    suspend fun getCodedValues(): List<CodedFieldValue>?
    /** Null for non-range domains or an unbounded endpoint. */
    suspend fun getMinimum(): FieldDomainBound?
    suspend fun getMaximum(): FieldDomainBound?
    suspend fun getGlob(): String?
    suspend fun getSplitPolicy(): Int
    suspend fun setSplitPolicy(policy: Int)
    suspend fun getMergePolicy(): Int
    suspend fun setMergePolicy(policy: Int)
}

suspend inline fun <T : FieldDomain, R> T.use(block: (T) -> R): R {
    try { return block(this) } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { close() }
    }
}
