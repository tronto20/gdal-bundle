package dev.gdal4k

import android.content.ContentResolver
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import java.io.IOException
import android.provider.DocumentsContract
import androidx.annotation.Keep
import kotlinx.serialization.json.*

internal data class AndroidUriInput(val uri: Uri, val resolver: ContentResolver)
internal data class AndroidDirectoryInput(val main: String, val resolver: ContentResolver, val entries: () -> Map<String, Uri>)

internal fun isAndroidInputUri(source: String): Boolean =
    source.substringBefore(':', "") in setOf("content", "file", "android.resource")

internal actual fun jniSourceAttachment(source: String): Any? =
    if (isAndroidInputUri(source)) AndroidUriInput(Uri.parse(source), AndroidResolvers.requireDefault()) else null

internal actual fun openJniInput(attachment: Any): JniInput {
    if (attachment is AndroidDirectoryInput) {
        val directory = AndroidUriDirectory(attachment.resolver, attachment.entries())
        require(attachment.main in directory.names) { "The main file was not found in the input directory: ${attachment.main}" }
        return JniInput(attachment.main, directory) { directory.close() }
    }
    val input = attachment as? AndroidUriInput ?: error("Unsupported Android input attachment")
    return openAndroidFile(input.uri, input.resolver)
}

private fun openAndroidFile(uri: Uri, resolver: ContentResolver): JniInput {
    val asset = resolver.openAssetFileDescriptor(uri, "r")
        ?: throw IOException("The content provider could not open the input URI")
    try {
        val offset = asset.startOffset
        val descriptor = asset.fileDescriptor
        val end = try {
            // Test the actual descriptor, not a MIME type or provider capability flag.
            val size = Os.lseek(descriptor, 0, OsConstants.SEEK_END)
            Os.lseek(descriptor, offset, OsConstants.SEEK_SET)
            size
        } catch (failure: android.system.ErrnoException) {
            throw IOException("The URI provider does not support seek; direct GDAL reading requires a seekable file", failure)
        }
        val length = if (asset.declaredLength >= 0) asset.declaredLength else end - offset
        if (offset < 0 || offset > end || length <= 0 || length > end - offset) {
            throw IOException("The URI provider returned an invalid file range")
        }
        // GDAL's subfile handler keeps reads within an AssetFileDescriptor's declared range.
        // /proc/self/fd reopens our own descriptor; no filesystem path or full-file copy is needed.
        val path = "/vsisubfile/${offset}_${length},/proc/self/fd/${asset.parcelFileDescriptor.fd}"
        return JniInput(path) { asset.close() }
    } catch (failure: Throwable) {
        try { asset.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
        throw failure
    }
}

internal fun requireInputName(name: String) {
    require(name.isNotEmpty() && name != "." && name != ".." && name.none { it in "/\\:\u0000" }) { "Expected a file name, not a path: $name" }
}

@Keep
internal class AndroidUriDirectory(private val resolver: ContentResolver, private val files: Map<String, Uri>) : JniDirectory(), AutoCloseable {
    val names = files.keys.also { it.forEach(::requireInputName) }
    private val opened = mutableMapOf<String, JniInput>()
    private var closed = false
    @Synchronized override fun openPath(name: ByteArray): ByteArray? {
        check(!closed) { "Android input directory is closed" }
        val key = name.decodeToString()
        val uri = files[key] ?: return null
        return opened.getOrPut(key) { openAndroidFile(uri, resolver) }.path.encodeToByteArray()
    }
    override fun listNames(): ByteArray = buildJsonObject { put("names", JsonArray(names.map(::JsonPrimitive))) }.toString().encodeToByteArray()
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        for (input in opened.values) try { input.close() } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        opened.clear()
        failure?.let { throw it }
    }
}

internal fun androidTreeFiles(tree: Uri, resolver: ContentResolver): Map<String, Uri> {
    require(DocumentsContract.isTreeUri(tree)) { "A directory URI granted by ACTION_OPEN_DOCUMENT_TREE is required" }
    val id = DocumentsContract.getTreeDocumentId(tree)
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
    val fields = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE)
    val result = linkedMapOf<String, Uri>()
    val cursor = resolver.query(children, fields, null, null, null) ?: throw IOException("The document provider could not list the directory")
    cursor.use {
        while (it.moveToNext()) {
            if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
            val name = it.getString(1)
            requireInputName(name)
            require(name !in result) { "The input directory contains duplicate file names: $name" }
            result[name] = DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
        }
    }
    return result
}
