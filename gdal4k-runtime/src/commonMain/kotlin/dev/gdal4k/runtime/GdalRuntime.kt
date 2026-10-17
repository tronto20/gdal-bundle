package dev.gdal4k.runtime

import dev.gdal4k.GdalDriver
import dev.gdal4k.GdalFiles

/**
 * Common GDAL entry point for dataset, spatial reference, and transformation operations.
 */
interface GdalRuntime {
    suspend fun versionInfo(request: String = "--version"): String

    /** Mount bytes in GDAL's process-local virtual filesystem. Path must begin with /vsimem/. */
    suspend fun writeMemoryFile(path: String, bytes: ByteArray)
    suspend fun readMemoryFile(path: String): ByteArray
    suspend fun deleteMemoryFile(path: String)

    suspend fun getDriver(name: String): Driver?

    suspend fun drivers(): List<GdalDriver>

    suspend fun createGCP(
        gcpX: Double = 0.0,
        gcpY: Double = 0.0,
        gcpZ: Double = 0.0,
        gcpPixel: Double = 0.0,
        gcpLine: Double = 0.0,
        info: String = "",
        id: String = "",
    ): GCP

    suspend fun createSpatialReference(): SpatialReference

    suspend fun createSpatialReference(definition: String): SpatialReference

    suspend fun createCoordinateTransformation(
        source: SpatialReference,
        target: SpatialReference,
    ): CoordinateTransformation?

    suspend fun openDataset(
        source: String,
        options: DatasetOpenOptions = DatasetOpenOptions(),
    ): Dataset

    /** Opens an isolated memory workspace; closing the returned dataset removes its files. */
    suspend fun openDataset(
        files: GdalFiles,
        options: DatasetOpenOptions = DatasetOpenOptions(),
    ): Dataset

    suspend fun datasetInfo(
        dataset: Dataset,
        options: DatasetInfoOptions = DatasetInfoOptions(),
    ): String
}

/**
 * Shared dataset handle. Platform-specific implementations will wrap the native dataset object.
 */
interface Dataset {
    suspend fun close()

    /** GDAL JSON information for a raster or vector dataset. */
    suspend fun info(arguments: List<String> = emptyList()): String

    suspend fun translate(
        driver: String = "GTiff",
        name: String = "output.tif",
        arguments: List<String> = emptyList(),
    ): GdalFiles

    suspend fun warp(
        target: String,
        driver: String = "GTiff",
        name: String = "warped.tif",
        arguments: List<String> = emptyList(),
    ): GdalFiles

    suspend fun translateVector(
        driver: String = "GeoJSON",
        name: String = "output.geojson",
        arguments: List<String> = emptyList(),
    ): GdalFiles

    suspend fun flushCache(): Int

    suspend fun getDriver(): Driver?

    /**
     * GDAL raster band indices are 1-based.
     */
    suspend fun getRasterBand(index: Int): Band?

    suspend fun getDriverShortName(): String

    suspend fun getDriverLongName(): String

    suspend fun getRasterXSize(): Int

    suspend fun getRasterYSize(): Int

    suspend fun getRasterCount(): Int

    suspend fun getLayerCount(): Int

    suspend fun getLayer(index: Int): Layer?

    suspend fun getLayer(name: String): Layer?

    suspend fun getProjection(): String

    suspend fun getProjectionRef(): String

    suspend fun setProjection(projectionWkt: String): Int

    suspend fun getGeoTransform(): DoubleArray?

    suspend fun setGeoTransform(geoTransform: DoubleArray): Int

    suspend fun getExtent(): DoubleArray?

    suspend fun getGCPCount(): Int

    suspend fun getGCPProjection(): String

    suspend fun getGCPSpatialRef(): SpatialReference?

    suspend fun getGCPs(): List<GCP>

    suspend fun setGCPs(gcps: List<GCP>, projection: String): Int

    suspend fun setGCPs(gcps: List<GCP>, spatialReference: SpatialReference): Int

    suspend fun getFileList(): List<String>

    suspend fun buildOverviews(
        resampling: String = "NEAREST",
        overviewBands: IntArray = intArrayOf(),
    ): Int

    suspend fun resetReading()

    suspend fun testCapability(capability: String): Boolean
}

suspend inline fun <T : Dataset, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { close() }
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
