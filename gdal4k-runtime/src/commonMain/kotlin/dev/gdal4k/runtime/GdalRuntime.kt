package dev.gdal4k.runtime

import dev.gdal4k.GdalDriver
import dev.gdal4k.GdalFiles

/**
 * Common GDAL entry point for dataset, spatial reference, and transformation operations.
 */
interface GdalRuntime {
    suspend fun createFeature(definition: FeatureDefn): Feature
    suspend fun createFieldDefn(name: String, type: Int): FieldDefn

    suspend fun createCodedFieldDomain(name: String, description: String, fieldType: Int, values: List<CodedFieldValue>, fieldSubType: Int = 0): FieldDomain
    suspend fun createRangeFieldDomain(name: String, description: String, fieldType: Int, minimum: FieldDomainBound?, maximum: FieldDomainBound?, fieldSubType: Int = 0): FieldDomain
    suspend fun createGlobFieldDomain(name: String, description: String, fieldType: Int, glob: String, fieldSubType: Int = 0): FieldDomain
    suspend fun createRelationship(name: String, leftTable: String, rightTable: String, cardinality: Int = 0): Relationship

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
    suspend fun getRootGroup(): Group?
    /** Returns an independent domain/relationship copy, owned by the caller. */
    suspend fun getFieldDomain(name: String): FieldDomain?
    suspend fun getFieldDomainNames(): List<String>
    suspend fun addFieldDomain(domain: FieldDomain)
    suspend fun updateFieldDomain(domain: FieldDomain)
    suspend fun deleteFieldDomain(name: String)
    suspend fun getRelationship(name: String): Relationship?
    suspend fun getRelationshipNames(): List<String>
    suspend fun addRelationship(relationship: Relationship)
    suspend fun updateRelationship(relationship: Relationship)
    suspend fun deleteRelationship(name: String)

    /** Description and metadata follow GDAL domains; XML domains retain their string-list representation. */
    suspend fun getDescription(): String
    suspend fun setDescription(description: String)
    suspend fun getMetadataDomainList(): List<String>
    suspend fun getMetadata(domain: String = ""): List<String>
    suspend fun setMetadata(metadata: List<String>, domain: String = ""): Int
    suspend fun getMetadataItem(name: String, domain: String = ""): String?
    /** A null value deletes the item. */
    suspend fun setMetadataItem(name: String, value: String?, domain: String = ""): Int
    /** Returns an independent copy, which the caller closes. */
    suspend fun getSpatialRef(): SpatialReference?
    suspend fun setSpatialRef(spatialReference: SpatialReference?): Int
    suspend fun getAccess(): Int
    suspend fun addBand(dataType: Int, options: List<String> = emptyList()): Int
    suspend fun createMaskBand(flags: Int): Int
    suspend fun clearStatistics()

    /** Writes directly to a GDAL/OS path. No output bytes are returned or copied through the bridge.
     * The caller owns output files, including any partial files after failure or cancellation.
     */
    suspend fun translateTo(path: String, driver: String = "GTiff", arguments: List<String> = emptyList())
    suspend fun warpTo(path: String, target: String, driver: String = "GTiff", arguments: List<String> = emptyList())
    suspend fun translateVectorTo(path: String, driver: String = "GeoJSON", arguments: List<String> = emptyList())

    suspend fun createLayer(name: String, spatialReference: SpatialReference? = null, geometryType: Int = 0, options: List<String> = emptyList()): Layer?
    suspend fun copyLayer(source: Layer, name: String, options: List<String> = emptyList()): Layer?
    /** Invalidates handles to the deleted layer and its borrowed children. Closes active SQL result sets before deleting. */
    suspend fun deleteLayer(index: Int): Int
    /** Close the returned result with use; SQL without a result set returns null. */
    suspend fun executeSQL(statement: String, spatialFilter: Geometry? = null, dialect: String = ""): Layer?
    suspend fun startTransaction(force: Boolean = false): Int
    suspend fun commitTransaction(): Int
    suspend fun rollbackTransaction(): Int

    suspend fun readRaster(buffer: ByteArray, options: RasterIOOptions = RasterIOOptions()): Int
    suspend fun readRaster(buffer: ShortArray, options: RasterIOOptions = RasterIOOptions()): Int
    suspend fun readRaster(buffer: IntArray, options: RasterIOOptions = RasterIOOptions()): Int
    suspend fun readRaster(buffer: LongArray, options: RasterIOOptions = RasterIOOptions()): Int
    suspend fun readRaster(buffer: FloatArray, options: RasterIOOptions = RasterIOOptions()): Int
    suspend fun readRaster(buffer: DoubleArray, options: RasterIOOptions = RasterIOOptions()): Int
    suspend fun writeRaster(buffer: ByteArray, options: RasterIOOptions = RasterIOOptions()): Int
    suspend fun writeRaster(buffer: ShortArray, options: RasterIOOptions = RasterIOOptions()): Int
    suspend fun writeRaster(buffer: IntArray, options: RasterIOOptions = RasterIOOptions()): Int
    suspend fun writeRaster(buffer: LongArray, options: RasterIOOptions = RasterIOOptions()): Int
    suspend fun writeRaster(buffer: FloatArray, options: RasterIOOptions = RasterIOOptions()): Int
    suspend fun writeRaster(buffer: DoubleArray, options: RasterIOOptions = RasterIOOptions()): Int

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
    Multidimensional,
}

data class DatasetOpenOptions(
    val accessMode: DatasetAccessMode = DatasetAccessMode.ReadOnly,
    val kind: DatasetKind = DatasetKind.Any,
    val openOptions: List<String> = emptyList(),
)

data class DatasetInfoOptions(
    val arguments: List<String> = emptyList(),
)
