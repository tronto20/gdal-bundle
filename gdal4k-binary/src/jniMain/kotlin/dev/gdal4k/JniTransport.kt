package dev.gdal4k

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class JniBridge {
    external fun create(proj: ByteArray, data: ByteArray): Long
    external fun execute(handle: Long, request: ByteArray): ByteArray
    external fun destroy(handle: Long)
    external fun mount(handle: Long, directory: JniDirectory): String
    external fun unmount(prefix: String)
}
internal expect fun loadBundledLibrary(path: String?)

internal actual suspend fun createTransport(options: GdalOptions): GdalTransport = withContext(Dispatchers.IO + NonCancellable) {
    loadBundledLibrary(options.libraryPath)
    val bridge = JniBridge()
    val handle = bridge.create(options.projData.orEmpty().encodeToByteArray(), options.gdalData.orEmpty().encodeToByteArray())
    check(handle != 0L) { "Unable to initialize GDAL" }
    object : GdalTransport {
        private var closed = false
        private val inputs = JniInputs({ bridge.mount(handle, it) }, bridge::unmount)
        @Synchronized private fun callNow(request: String, attachment: Any?): String {
            check(!closed) { "GDAL runtime is closed" }
            return inputs.execute(request, attachment) { bridge.execute(handle, it.encodeToByteArray()).decodeToString() }
        }
        @Synchronized private fun closeNow() {
            if (!closed) {
                try { bridge.destroy(handle) } finally { closed = true; inputs.close() }
            }
        }
        override suspend fun call(request: String, attachment: Any?): String = withContext(Dispatchers.IO + NonCancellable) { callNow(request, attachment) }
        override suspend fun close() = withContext(Dispatchers.IO + NonCancellable) { closeNow() }
    }
}
