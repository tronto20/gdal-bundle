package dev.gdal4k.runtime

import dev.gdal4k.internal.RuntimeHandle
import dev.gdal4k.internal.wrapDataset
import org.w3c.files.File
import dev.gdal4k.createWebInputSet
import kotlinx.serialization.json.*

/** Selected files share one virtual directory; GDAL opens sidecars on demand. */
suspend fun GdalRuntime.openDataset(
    main: String,
    files: List<File>,
    options: DatasetOpenOptions = DatasetOpenOptions(),
): Dataset {
    require(files.any { it.name == main }) { "The selected files do not contain the main file: $main" }
    require(files.map { it.name }.distinct().size == files.size) { "Selected file names must be unique" }
    return openWebInputs(main, createWebInputSet(files, null), options)
}

/** Maps GDAL's related file names to explicit HTTP URLs, including individually signed URLs. */
suspend fun GdalRuntime.openDataset(
    source: String,
    relatedFiles: Map<String, String>,
    options: DatasetOpenOptions = DatasetOpenOptions(),
): Dataset {
    require(source.startsWith("https://") || source.startsWith("http://")) { "HTTP(S) input is required" }
    val urls = JsonObject(relatedFiles.mapValues { JsonPrimitive(it.value) }).toString()
    return openWebInputs(source, createWebInputSet(null, urls), options)
}

/** Reads a selected browser file by range in the GDAL Worker, without copying the whole file. */
suspend fun GdalRuntime.openDataset(file: File, options: DatasetOpenOptions = DatasetOpenOptions()): Dataset {
    return openWebInputs(file.name, file, options)
}

private suspend fun GdalRuntime.openWebInputs(source: String, attachment: Any, options: DatasetOpenOptions): Dataset {
    require(options.accessMode == DatasetAccessMode.ReadOnly) { "Browser files are read-only" }
    val runtime = this as? RuntimeHandle ?: error("Browser files require the bundled GDAL runtime")
    runtime.checkOpen()
    val value = runtime.engine.invoke("GdalRuntime", "0", "openDataset_String_DatasetOpenOptions",
        mapOf("source" to runtime.encode(source), "options" to runtime.encode(options)), attachment)
    return requireNotNull(runtime.engine.wrapDataset(value, null))
}
