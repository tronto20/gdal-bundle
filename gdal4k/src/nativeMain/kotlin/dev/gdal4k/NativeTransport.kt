@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.gdal4k

import dev.gdal4k.cinterop.*
import kotlinx.cinterop.*

internal actual suspend fun createTransport(options: GdalOptions): GdalTransport {
    val handle = gdal4k_create(options.projData, options.gdalData) ?: error("Unable to initialize GDAL")
    return object : GdalTransport {
        override suspend fun call(request: String): String {
            val result = gdal4k_execute(handle, request) ?: error("GDAL bridge returned null")
            return try { result.toKString() } finally { gdal4k_free(result) }
        }
        override suspend fun close() { gdal4k_destroy(handle) }
    }
}
