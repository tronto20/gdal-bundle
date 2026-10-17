package dev.gdal4k

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class JniBridge {
    external fun create(proj: ByteArray, data: ByteArray): Long
    external fun execute(handle: Long, request: ByteArray): ByteArray
    external fun destroy(handle: Long)
}
internal expect fun loadBundledLibrary(path: String?)

internal actual suspend fun createTransport(options: GdalOptions): GdalTransport = withContext(Dispatchers.IO + NonCancellable) {
    loadBundledLibrary(options.libraryPath)
    val bridge = JniBridge()
    val handle = bridge.create(options.projData.orEmpty().encodeToByteArray(), options.gdalData.orEmpty().encodeToByteArray())
    check(handle != 0L) { "Unable to initialize GDAL" }
    object : GdalTransport {
        private var closed = false
        @Synchronized private fun callNow(request: String): String {
            check(!closed) { "GDAL runtime is closed" }
            return bridge.execute(handle, request.encodeToByteArray()).decodeToString()
        }
        @Synchronized private fun closeNow() {
            if (!closed) { bridge.destroy(handle); closed = true }
        }
        override suspend fun call(request: String, attachment: Any?): String = withContext(Dispatchers.IO + NonCancellable) { require(attachment == null) { "Browser files require JS/wasmJs" }; callNow(request) }
        override suspend fun close() = withContext(Dispatchers.IO + NonCancellable) { closeNow() }
    }
}
