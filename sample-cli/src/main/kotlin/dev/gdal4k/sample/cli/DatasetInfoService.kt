package dev.gdal4k.sample.cli

import dev.gdal4k.runtime.DatasetInfoOptions
import dev.gdal4k.runtime.GdalRuntime
import dev.gdal4k.runtime.use

class DatasetInfoService(
    private val runtime: GdalRuntime,
) {
    fun describe(datasetPath: String, infoArgs: List<String> = emptyList()): String {
        return runtime.openDataset(datasetPath).use { dataset ->
            runtime.datasetInfo(dataset, DatasetInfoOptions(arguments = infoArgs))
        }
    }
}
