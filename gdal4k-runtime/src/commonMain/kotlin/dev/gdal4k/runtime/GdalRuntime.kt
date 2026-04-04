package dev.gdal4k.runtime

/**
 * Common GDAL entry point for dataset-oriented operations.
 */
interface GdalRuntime {
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
