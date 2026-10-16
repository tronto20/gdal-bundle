package dev.gdal4k

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Extracts the AAR's PROJ/GDAL data into application-owned storage. No storage permission required. */
object GdalAndroid {
    private val mutex = Mutex()
    suspend fun options(context: Context): GdalOptions = withContext(Dispatchers.IO) {
        mutex.withLock {
            val assets = context.applicationContext.assets
            val manifest = assets.open("gdal4k/manifest.txt").use { it.readBytes() }
            val version = MessageDigest.getInstance("SHA-256").digest(manifest).joinToString("") { "%02x".format(it) }
            val directory = File(context.filesDir, "gdal4k/$version")
            val ready = File(directory, ".ready")
            if (!ready.isFile) {
                check(directory.mkdirs() || directory.isDirectory)
                manifest.decodeToString().lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    val (digest, name) = line.split("  ", limit = 2)
                    check(!name.startsWith("/") && name.split('/').none { it == ".." })
                    val output = File(directory, name)
                    check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory)
                    val data = assets.open("gdal4k/$name").use { it.readBytes() }
                    check(MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) } == digest) { "Invalid GDAL data asset: $name" }
                    output.outputStream().use { it.write(data) }
                }
                ready.writeText(version)
            }
            GdalOptions(projData = File(directory, "proj").absolutePath, gdalData = File(directory, "gdal").absolutePath)
        }
    }
    suspend fun create(context: Context): GdalSession = Gdal.create(options(context))
}
