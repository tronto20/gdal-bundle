package dev.gdal4k

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Both Android and JVM use this small JNI bridge; no desktop-only filesystem or archive APIs.
internal class JniBridge {
    external fun create(proj: ByteArray, data: ByteArray): Long
    external fun execute(handle: Long, request: ByteArray): ByteArray
    external fun destroy(handle: Long)
    companion object {
        @Synchronized fun load(path: String?) {
            if (path == null) System.loadLibrary("gdal4k") else System.load(path)
        }
    }
}
internal actual suspend fun createTransport(options: GdalOptions): GdalTransport = withContext(Dispatchers.IO) {
    JniBridge.load(options.libraryPath)
    val bridge = JniBridge()
    val handle = bridge.create(options.projData.orEmpty().encodeToByteArray(), options.gdalData.orEmpty().encodeToByteArray())
    check(handle != 0L) { "Unable to initialize GDAL" }
    object : GdalTransport {
        override suspend fun call(request: String): String = withContext(Dispatchers.IO) {
            bridge.execute(handle, request.encodeToByteArray()).decodeToString()
        }
        override suspend fun close() = withContext(Dispatchers.IO) { bridge.destroy(handle) }
    }
}
