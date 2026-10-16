package dev.gdal4k.runtime

/**
 * Shared vector layer handle.
 *
 * The first pass keeps the surface focused on layer metadata, filtering, and iteration state.
 */
interface Layer {
    suspend fun close()

    suspend fun getDataset(): Dataset?

    suspend fun getLayerDefn(): FeatureDefn?

    suspend fun getName(): String

    suspend fun getGeomType(): Int

    suspend fun getGeometryColumn(): String

    suspend fun getFIDColumn(): String

    suspend fun rename(name: String): Int

    suspend fun getRefCount(): Int

    suspend fun createGeomField(geomFieldDefn: GeomFieldDefn, flags: Int = 0): Int

    suspend fun setAttributeFilter(filter: String): Int

    suspend fun resetReading()

    suspend fun getFeature(fid: Long): Feature?

    suspend fun getNextFeature(): Feature?

    suspend fun createFeature(feature: Feature): Int

    suspend fun setFeature(feature: Feature): Int

    suspend fun upsertFeature(feature: Feature): Int

    suspend fun deleteFeature(fid: Long): Int

    suspend fun setNextByIndex(index: Long): Int

    suspend fun getFeatureCount(force: Boolean = false): Long

    suspend fun getExtent(force: Boolean = false): DoubleArray?

    suspend fun getFeaturesRead(): Long

    suspend fun syncToDisk(): Int

    suspend fun testCapability(capability: String): Boolean
}

suspend inline fun <T : Layer, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { close() }
    }
}
