package dev.gdal4k.binary

import dev.gdal4k.runtime.Dataset
import dev.gdal4k.runtime.DatasetAccessMode
import dev.gdal4k.runtime.DatasetInfoOptions
import dev.gdal4k.runtime.DatasetKind
import dev.gdal4k.runtime.DatasetOpenOptions
import dev.gdal4k.runtime.GdalPlatform
import dev.gdal4k.runtime.GdalRuntime
import org.gdal.gdal.Dataset as NativeDataset
import org.gdal.gdal.InfoOptions
import org.gdal.gdal.gdal
import org.gdal.gdalconst.gdalconstConstants
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.Vector
import java.security.MessageDigest

actual object Gdal4kBinary {
    @Volatile
    private var runtime: GdalRuntime? = null

    actual fun runtime(bundleDir: String?): GdalRuntime {
        runtime?.let { return it }
        synchronized(this) {
            runtime?.let { return it }
            val loaded = JvmGdalRuntime.initialize(bundleDir)
            runtime = loaded
            return loaded
        }
    }
}

private class JvmGdalRuntime private constructor() : GdalRuntime {
    override fun openDataset(source: String, options: DatasetOpenOptions): Dataset {
        val nativeDataset = openNativeDataset(source, options)
        return JvmDataset(nativeDataset)
    }

    override fun datasetInfo(dataset: Dataset, options: DatasetInfoOptions): String {
        val nativeDataset = (dataset as? JvmDataset)?.delegate
            ?: throw IllegalArgumentException("Dataset was not created by the JVM GDAL runtime.")

        val infoOptions = InfoOptions(stringVector(options.arguments))
        return try {
            gdal.GDALInfo(nativeDataset, infoOptions)
                ?.trimEnd()
                ?.ifBlank { "gdalinfo returned empty output." }
                ?: "gdalinfo returned empty output."
        } finally {
            infoOptions.delete()
        }
    }

    companion object {
        fun initialize(bundleDir: String?): GdalRuntime {
            val gdalDir = locateBundleDir(bundleDir)
            loadBundle(gdalDir)
            return JvmGdalRuntime()
        }

        private fun openNativeDataset(source: String, options: DatasetOpenOptions): NativeDataset {
            val dataset = if (options.kind == DatasetKind.Any && options.openOptions.isEmpty()) {
                when (options.accessMode) {
                    DatasetAccessMode.ReadOnly -> gdal.Open(source)
                    DatasetAccessMode.Update -> gdal.Open(source, gdalconstConstants.GA_Update)
                }
            } else {
                val flags = openFlags(options)
                val openOptions = options.openOptions.takeIf { it.isNotEmpty() }?.let(::stringVector)
                if (openOptions != null) {
                    gdal.OpenEx(source, flags, null, openOptions)
                } else {
                    gdal.OpenEx(source, flags)
                }
            }

            return dataset
                ?: throw IllegalStateException("Unable to open dataset: $source\n${gdal.GetLastErrorMsg()}")
        }

        private fun openFlags(options: DatasetOpenOptions): Long {
            var flags = gdalconstConstants.OF_VERBOSE_ERROR.toLong()
            flags = flags or when (options.accessMode) {
                DatasetAccessMode.ReadOnly -> gdalconstConstants.OF_READONLY.toLong()
                DatasetAccessMode.Update -> gdalconstConstants.OF_UPDATE.toLong()
            }
            flags = flags or when (options.kind) {
                DatasetKind.Any -> 0L
                DatasetKind.Raster -> gdalconstConstants.OF_RASTER.toLong()
                DatasetKind.Vector -> gdalconstConstants.OF_VECTOR.toLong()
            }
            return flags
        }

        private fun loadBundle(bundleDir: File) {
            require(bundleDir.exists() && bundleDir.isDirectory) {
                "GDAL bundle directory not found: ${bundleDir.absolutePath}"
            }

            val nativeLibraryName = System.mapLibraryName("gdalalljni")
            val nativeLibrary = File(bundleDir, "lib/$nativeLibraryName")
            require(nativeLibrary.exists()) {
                "JNI library not found: ${nativeLibrary.absolutePath}"
            }

            System.load(nativeLibrary.absolutePath)

            val dataDir = File(bundleDir, "share/gdal")
            val projDir = File(bundleDir, "share/proj")
            require(dataDir.exists()) {
                "GDAL data directory not found: ${dataDir.absolutePath}"
            }
            require(projDir.exists()) {
                "PROJ data directory not found: ${projDir.absolutePath}"
            }

            gdal.SetConfigOption("GDAL_DATA", dataDir.absolutePath)
            gdal.SetConfigOption("PROJ_DATA", projDir.absolutePath)

            val pluginsDir = File(bundleDir, "gdalplugins")
            if (pluginsDir.exists()) {
                gdal.SetConfigOption("GDAL_DRIVER_PATH", pluginsDir.absolutePath)
            }

            gdal.AllRegister()
        }

        private fun locateBundleDir(bundleDir: String?): File {
            val platform = System.getProperty("gdal.bundle.platform")
                ?.takeIf { it.isNotBlank() }
                ?: GdalPlatform.current().classifier

            val candidates = linkedSetOf<File>()
            bundleDir?.takeIf { it.isNotBlank() }?.let { candidates += File(it) }

            val explicit = System.getProperty("gdal.bundle.dir") ?: System.getenv("GDAL_BUNDLE_DIR")
            if (!explicit.isNullOrBlank()) {
                candidates += File(explicit)
            }

            val resourcesDir = System.getProperty("compose.application.resources.dir")
            if (!resourcesDir.isNullOrBlank()) {
                candidates += File(resourcesDir)
            }

            for (candidate in candidates) {
                resolveBundleCandidate(candidate, platform)?.let { return it }
            }

            val checked = candidates.joinToString("\n") { "- ${it.absolutePath}" }
            throw IllegalStateException(
                buildString {
                    append("GDAL bundle not found for platform ")
                    append(platform)
                    append(". Set gdal.bundle.dir or GDAL_BUNDLE_DIR, or pass bundleDir to Gdal4kBinary.runtime().")
                    if (checked.isNotBlank()) {
                        append("\nChecked roots:\n")
                        append(checked)
                    }
                },
            )
        }

        private fun resolveBundleCandidate(candidate: File, platform: String): File? {
            if (!candidate.exists()) {
                return null
            }

            if (candidate.isFile) {
                if (isTxzArchive(candidate)) {
                    return extractTxzArchive(candidate, platform)
                }
                return null
            }

            findArchiveCandidate(candidate, platform)?.let { archive ->
                return extractTxzArchive(archive, platform)
            }

            return normalizeBundleDir(candidate, platform)
        }

        private fun findArchiveCandidate(directory: File, platform: String): File? {
            val candidates = listOf(
                File(directory, "gdal.txz"),
                File(directory, "$platform.txz"),
                File(File(directory, platform), "gdal.txz"),
                File(File(directory, "gdal"), "$platform.txz"),
            )

            return candidates.firstOrNull { it.isFile && isTxzArchive(it) }
        }

        private fun isTxzArchive(file: File): Boolean {
            val name = file.name.lowercase()
            return name.endsWith(".txz") || name.endsWith(".tar.xz")
        }

        private fun extractTxzArchive(archive: File, platform: String): File {
            val cacheRoot = File(File(System.getProperty("java.io.tmpdir"), "gdal4k-cache"), platform)
            val cacheKey = sha256(archive)
            val extractRoot = File(cacheRoot, cacheKey)
            val marker = File(extractRoot, ".complete")

            if (marker.isFile) {
                return requireNotNull(normalizeBundleDir(extractRoot, platform)) {
                    "Cached GDAL bundle extraction is incomplete: ${extractRoot.absolutePath}"
                }
            }

            extractRoot.deleteRecursively()
            extractRoot.mkdirs()
            unpackTxz(archive, extractRoot.toPath())
            marker.writeText("${archive.absolutePath}\n${archive.length()}\n${archive.lastModified()}")

            return requireNotNull(normalizeBundleDir(extractRoot, platform)) {
                "Extracted GDAL bundle is missing expected files: ${extractRoot.absolutePath}"
            }
        }

        private fun unpackTxz(archive: File, destination: Path) {
            val destinationRoot = destination.toAbsolutePath().normalize()
            FileInputStream(archive).use { fileInput ->
                BufferedInputStream(fileInput).use { buffered ->
                    XZCompressorInputStream(buffered).use { xzInput ->
                        TarArchiveInputStream(xzInput).use { tarInput ->
                            while (true) {
                                val entry = tarInput.nextTarEntry ?: break
                                val outputPath = destinationRoot.resolve(entry.name).normalize()
                                require(outputPath.startsWith(destinationRoot)) {
                                    "Blocked suspicious archive entry: ${entry.name}"
                                }
                                extractTarEntry(tarInput, entry, outputPath)
                            }
                        }
                    }
                }
            }
        }

        private fun extractTarEntry(tarInput: TarArchiveInputStream, entry: TarArchiveEntry, outputPath: Path) {
            when {
                entry.isDirectory -> {
                    Files.createDirectories(outputPath)
                    applyPermissions(outputPath, entry.mode)
                }

                entry.isSymbolicLink -> {
                    Files.createDirectories(outputPath.parent)
                    Files.deleteIfExists(outputPath)
                    Files.createSymbolicLink(outputPath, Paths.get(entry.linkName))
                }

                else -> {
                    Files.createDirectories(outputPath.parent)
                    Files.newOutputStream(outputPath).use { output ->
                        copyLimited(tarInput, output, entry.size)
                    }
                    applyPermissions(outputPath, entry.mode)
                }
            }
        }

        private fun copyLimited(input: TarArchiveInputStream, output: java.io.OutputStream, size: Long) {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = size
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read < 0) {
                    throw EOFException("Unexpected end of archive while extracting GDAL bundle.")
                }
                output.write(buffer, 0, read)
                remaining -= read.toLong()
            }
        }

        private fun applyPermissions(path: Path, mode: Int) {
            if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) == null) {
                return
            }
            val permissions = mutableSetOf<PosixFilePermission>()

            if (mode and 0b100_000_000 != 0) permissions += PosixFilePermission.OWNER_READ
            if (mode and 0b010_000_000 != 0) permissions += PosixFilePermission.OWNER_WRITE
            if (mode and 0b001_000_000 != 0) permissions += PosixFilePermission.OWNER_EXECUTE
            if (mode and 0b000_100_000 != 0) permissions += PosixFilePermission.GROUP_READ
            if (mode and 0b000_010_000 != 0) permissions += PosixFilePermission.GROUP_WRITE
            if (mode and 0b000_001_000 != 0) permissions += PosixFilePermission.GROUP_EXECUTE
            if (mode and 0b000_000_100 != 0) permissions += PosixFilePermission.OTHERS_READ
            if (mode and 0b000_000_010 != 0) permissions += PosixFilePermission.OTHERS_WRITE
            if (mode and 0b000_000_001 != 0) permissions += PosixFilePermission.OTHERS_EXECUTE

            try {
                Files.setPosixFilePermissions(path, permissions)
            } catch (_: UnsupportedOperationException) {
                // Windows and some non-POSIX filesystems do not support permission attributes.
            }
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        }

        private fun normalizeBundleDir(directory: File, platform: String): File? {
            val candidates = listOf(
                directory,
                File(directory, "gdal"),
                File(directory, platform),
                File(directory, "$platform/gdal"),
                File(File(directory, "gdal"), platform),
                File(File(directory, platform), "gdal"),
            )

            return candidates.firstOrNull { isBundleLayout(it) }
        }

        private fun stringVector(values: List<String>): Vector<String> {
            return Vector<String>().apply {
                values.forEach { add(it) }
            }
        }

        private fun isBundleLayout(directory: File): Boolean {
            if (!directory.exists() || !directory.isDirectory) {
                return false
            }

            val nativeLibraryName = System.mapLibraryName("gdalalljni")
            return File(directory, "lib/$nativeLibraryName").exists()
        }
    }
}

private class JvmDataset(
    val delegate: NativeDataset,
) : Dataset {
    @Volatile
    private var closed = false

    override fun close() {
        if (closed) {
            return
        }
        synchronized(this) {
            if (!closed) {
                delegate.delete()
                closed = true
            }
        }
    }
}
