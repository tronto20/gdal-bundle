package dev.gdal4k.runtime

import dev.gdal4k.binary.RuntimeHandle
import dev.gdal4k.binary.wrapDataset
import org.w3c.files.File

/** Reads a selected browser file by range in the GDAL Worker, without copying the whole file. */
suspend fun GdalRuntime.openDataset(file: File, options: DatasetOpenOptions = DatasetOpenOptions()): Dataset {
    require(options.accessMode == DatasetAccessMode.ReadOnly) { "Browser files are read-only" }
    val runtime = this as? RuntimeHandle ?: error("Browser files require the bundled GDAL runtime")
    runtime.checkOpen()
    val value = runtime.engine.invoke("GdalRuntime", "0", "openDataset_String_DatasetOpenOptions",
        mapOf("source" to runtime.encode(file.name), "options" to runtime.encode(options)), file)
    return requireNotNull(runtime.engine.wrapDataset(value, null))
}
