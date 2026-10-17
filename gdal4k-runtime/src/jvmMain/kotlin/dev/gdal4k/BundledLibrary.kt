package dev.gdal4k

import dev.gdal4k.runtime.GdalPlatform
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private object BundledLibrary {
    private val loaded = mutableSetOf<String>()
    private var bundledLoaded = false
    @Synchronized fun load(path: String?) {
        if (path != null) {
            val canonical = java.io.File(path).canonicalPath
            if (loaded.add(canonical)) try { System.load(canonical) } catch (e: Throwable) { loaded.remove(canonical); throw e }
            return
        }
        if (bundledLoaded) return
        val name = System.mapLibraryName("gdal4k")
        val platform = GdalPlatform.current().classifier
        val resource = "/gdal4k/$platform/$name"
        val artifact = when (platform) {
            "linux-amd64" -> "linux-x64-gnu"
            "linux-arm64" -> "linux-arm64-gnu"
            "windows-amd64" -> "windows-x64"
            else -> platform
        }
        val bytes = checkNotNull(javaClass.getResourceAsStream(resource)) {
            "Missing GDAL JVM binary for $platform. Add runtimeOnly(\"dev.tronto.gdal4k:gdal4k-engine-jvm-$artifact:<gdal4k-version>\") " +
                "using the same version as gdal4k. No JVM binary is included by default. Missing resource: $resource"
        }.use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val directory = java.io.File(System.getProperty("java.io.tmpdir"), "gdal4k/$digest").toPath()
        Files.createDirectories(directory)
        val destination = directory.resolve(name)
        java.nio.channels.FileChannel.open(directory.resolve(".lock"), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val valid = Files.isRegularFile(destination) && MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(destination)), MessageDigest.getInstance("SHA-256").digest(bytes))
                if (!valid) {
                    val temporary = Files.createTempFile(directory, name, ".part")
                    try {
                        Files.write(temporary, bytes)
                        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } finally { Files.deleteIfExists(temporary) }
                }
            }
        }
        load(destination.toAbsolutePath().toString())
        bundledLoaded = true
    }
}
internal actual fun loadBundledLibrary(path: String?) = BundledLibrary.load(path)
