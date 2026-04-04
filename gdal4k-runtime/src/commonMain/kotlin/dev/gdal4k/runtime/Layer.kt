package dev.gdal4k.runtime

/**
 * Shared vector layer handle.
 *
 * The first pass keeps the surface focused on layer metadata, filtering, and iteration state.
 */
interface Layer {
    fun close()

    fun getDataset(): Dataset?

    fun getLayerDefn(): FeatureDefn?

    fun getName(): String

    fun getGeomType(): Int

    fun getGeometryColumn(): String

    fun getFIDColumn(): String

    fun rename(name: String): Int

    fun getRefCount(): Int

    fun createGeomField(geomFieldDefn: GeomFieldDefn, flags: Int = 0): Int

    fun setAttributeFilter(filter: String): Int

    fun resetReading()

    fun getFeature(fid: Long): Feature?

    fun getNextFeature(): Feature?

    fun createFeature(feature: Feature): Int

    fun setFeature(feature: Feature): Int

    fun upsertFeature(feature: Feature): Int

    fun deleteFeature(fid: Long): Int

    fun setNextByIndex(index: Long): Int

    fun getFeatureCount(force: Boolean = false): Long

    fun getExtent(force: Boolean = false): DoubleArray?

    fun getFeaturesRead(): Long

    fun syncToDisk(): Int

    fun testCapability(capability: String): Boolean
}

inline fun <T : Layer, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
