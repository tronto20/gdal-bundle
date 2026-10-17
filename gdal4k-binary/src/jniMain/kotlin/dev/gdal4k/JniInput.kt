package dev.gdal4k

/** A seek-checked, owned source. The descriptor stays open until GDAL closes its dataset. */
internal class JniInput(val path: String, val directory: JniDirectory? = null, private val release: () -> Unit) : AutoCloseable {
    override fun close() = release()
}

/** Methods called by the native VSI directory; arguments use UTF-8, including non-BMP names. */
internal abstract class JniDirectory {
    abstract fun openPath(name: ByteArray): ByteArray?
    abstract fun listNames(): ByteArray
}

internal expect fun openJniInput(attachment: Any): JniInput

/** Resolves platform URI strings; ordinary GDAL paths keep their native handling. */
internal expect fun jniSourceAttachment(source: String): Any?
