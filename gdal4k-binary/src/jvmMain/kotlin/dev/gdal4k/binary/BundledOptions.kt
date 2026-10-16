package dev.gdal4k.binary

import dev.gdal4k.GdalOptions
import java.io.File
internal actual fun bundledOptions(bundleDir: String?): GdalOptions = if (bundleDir == null) GdalOptions() else {
    val root = File(bundleDir)
    val name = System.mapLibraryName("gdal4k")
    GdalOptions(libraryPath = listOf(File(root, name), File(root, "lib/$name")).firstOrNull { it.isFile }?.absolutePath
        ?: error("GDAL bridge is missing from the explicit bundle directory: $root"))
}
