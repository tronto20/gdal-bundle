package dev.gdal4k.runtime

import android.content.ContentResolver
import android.net.Uri
import dev.gdal4k.AndroidResolvers
import dev.gdal4k.AndroidUriInput
import dev.gdal4k.AndroidDirectoryInput
import dev.gdal4k.androidTreeFiles
import dev.gdal4k.requireInputName
import dev.gdal4k.isAndroidInputUri
import dev.gdal4k.internal.RuntimeHandle
import dev.gdal4k.internal.wrapDataset
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Overrides the resolver for an Android URI string. Ordinary paths retain native GDAL handling.
 * Without a resolver argument the common String overload automatically uses the application resolver.
 */
suspend fun GdalRuntime.openDataset(
    source: String,
    resolver: ContentResolver,
    options: DatasetOpenOptions = DatasetOpenOptions(),
): Dataset = if (isAndroidInputUri(source)) openDataset(Uri.parse(source), resolver, options)
    else openDataset(source, options)

/**
 * Opens a seekable Android URI without copying its contents. The dataset owns the descriptor.
 * By default uses the application resolver registered by App Startup. An explicit resolver
 * takes precedence and also works when automatic resolver registration is disabled.
 * URI permission acquisition/persistence remains the application's responsibility.
 */
suspend fun GdalRuntime.openDataset(
    uri: Uri,
    resolver: ContentResolver = AndroidResolvers.requireDefault(),
    options: DatasetOpenOptions = DatasetOpenOptions(),
): Dataset {
    require(uri.scheme in setOf("content", "file", "android.resource")) { "Unsupported Android URI scheme: ${uri.scheme}" }
    require(options.accessMode == DatasetAccessMode.ReadOnly) { "Android URI inputs are read-only" }
    return openAndroidInput(uri.toString(), AndroidUriInput(uri, resolver), options)
}

/** Exposes named URIs as one read-only directory; files are opened only when GDAL requests them. */
suspend fun GdalRuntime.openDataset(
    main: String,
    files: Map<String, Uri>,
    resolver: ContentResolver = AndroidResolvers.requireDefault(),
    options: DatasetOpenOptions = DatasetOpenOptions(),
): Dataset {
    requireInputName(main)
    val entries = files.toMap()
    require(main in entries) { "The input files do not contain the main file: $main" }
    entries.keys.forEach(::requireInputName)
    return openAndroidInput(main, AndroidDirectoryInput(main, resolver) { entries }, options)
}

/** Lists a granted SAF tree and exposes its immediate files as a read-only directory. */
suspend fun GdalRuntime.openDataset(
    main: String,
    directory: Uri,
    resolver: ContentResolver = AndroidResolvers.requireDefault(),
    options: DatasetOpenOptions = DatasetOpenOptions(),
): Dataset {
    requireInputName(main)
    return openAndroidInput(main, AndroidDirectoryInput(main, resolver) { androidTreeFiles(directory, resolver) }, options)
}

private suspend fun GdalRuntime.openAndroidInput(source: String, attachment: Any, options: DatasetOpenOptions): Dataset {
    require(options.accessMode == DatasetAccessMode.ReadOnly) { "Android URI inputs are read-only" }
    val runtime = this as? RuntimeHandle ?: error("Android URI inputs require the bundled GDAL runtime")
    runtime.checkOpen()
    val caller = currentCoroutineContext()
    caller.ensureActive()
    return withContext(NonCancellable) {
        val value = runtime.engine.invoke("GdalRuntime", "0", "openDataset_String_DatasetOpenOptions",
            mapOf("source" to runtime.encode(source), "options" to runtime.encode(options)), attachment)
        val dataset = requireNotNull(runtime.engine.wrapDataset(value, null))
        try { caller.ensureActive(); dataset } catch (failure: Throwable) {
            try { dataset.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            throw failure
        }
    }
}
