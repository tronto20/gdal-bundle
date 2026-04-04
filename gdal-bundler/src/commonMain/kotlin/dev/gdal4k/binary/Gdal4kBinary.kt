package dev.gdal4k.binary

import dev.gdal4k.runtime.GdalRuntime

expect object Gdal4kBinary {
    fun runtime(bundleDir: String? = null): GdalRuntime
}
