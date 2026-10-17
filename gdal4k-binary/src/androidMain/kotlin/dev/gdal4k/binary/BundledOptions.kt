package dev.gdal4k.binary
import dev.gdal4k.GdalOptions
internal actual fun bundledOptions(bundleDir: String?): GdalOptions = GdalOptions(libraryPath = bundleDir?.let { "$it/libgdal4k.so" })
