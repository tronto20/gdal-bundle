@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.gdal4k

import dev.gdal4k.cinterop.*
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal actual suspend fun createTransport(options: GdalOptions): GdalTransport = withContext(Dispatchers.Default + NonCancellable) {
    val handle = gdal4k_create(options.projData, options.gdalData) ?: error("Unable to initialize GDAL")
    object : GdalTransport {
        private fun callNow(request: String): String {
            val result = gdal4k_execute(handle, request) ?: error("GDAL bridge returned null")
            return try { result.toKString() } finally { gdal4k_free(result) }
        }
        private fun closeNow() { gdal4k_destroy(handle) }
        override suspend fun call(request: String): String = withContext(Dispatchers.Default + NonCancellable) { callNow(request) }
        override suspend fun close() = withContext(Dispatchers.Default + NonCancellable) { closeNow() }
    }
}
