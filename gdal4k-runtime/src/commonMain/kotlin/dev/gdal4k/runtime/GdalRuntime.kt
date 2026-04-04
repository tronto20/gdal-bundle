package dev.gdal4k.runtime

/**
 * Common GDAL entry point for dataset, spatial reference, and transformation operations.
 */
interface GdalRuntime {
    fun getDriver(name: String): Driver?

    fun createSpatialReference(): SpatialReference

    fun createSpatialReference(definition: String): SpatialReference

    fun createCoordinateTransformation(
        source: SpatialReference,
        target: SpatialReference,
    ): CoordinateTransformation?

    fun openDataset(
        source: String,
        options: DatasetOpenOptions = DatasetOpenOptions(),
    ): Dataset

    fun datasetInfo(
        dataset: Dataset,
        options: DatasetInfoOptions = DatasetInfoOptions(),
    ): String
}

/**
 * Shared dataset handle. Platform-specific implementations will wrap the native dataset object.
 */
interface Dataset {
    fun close()

    fun flushCache(): Int

    fun getDriver(): Driver?

    /**
     * GDAL raster band indices are 1-based.
     */
    fun getRasterBand(index: Int): Band?

    fun getDriverShortName(): String

    fun getDriverLongName(): String

    fun getRasterXSize(): Int

    fun getRasterYSize(): Int

    fun getRasterCount(): Int

    fun getLayerCount(): Int

    fun getLayer(index: Int): Layer?

    fun getLayer(name: String): Layer?

    fun getProjection(): String

    fun getProjectionRef(): String

    fun setProjection(projectionWkt: String): Int

    fun getGeoTransform(): DoubleArray?

    fun setGeoTransform(geoTransform: DoubleArray): Int

    fun getExtent(): DoubleArray?

    fun getGCPCount(): Int

    fun getGCPProjection(): String

    fun getFileList(): List<String>

    fun buildOverviews(
        resampling: String = "NEAREST",
        overviewBands: IntArray = intArrayOf(),
    ): Int

    fun resetReading()

    fun testCapability(capability: String): Boolean
}

inline fun <T : Dataset, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}

enum class DatasetAccessMode {
    ReadOnly,
    Update,
}

enum class DatasetKind {
    Any,
    Raster,
    Vector,
}

data class DatasetOpenOptions(
    val accessMode: DatasetAccessMode = DatasetAccessMode.ReadOnly,
    val kind: DatasetKind = DatasetKind.Any,
    val openOptions: List<String> = emptyList(),
)

data class DatasetInfoOptions(
    val arguments: List<String> = emptyList(),
)
