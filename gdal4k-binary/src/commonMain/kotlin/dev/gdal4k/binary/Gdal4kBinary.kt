package dev.gdal4k.binary

import dev.gdal4k.runtime.GdalRuntime

expect object Gdal4kBinary {
    suspend fun prepare(bundleDir: String? = null)
    fun runtime(): GdalRuntime
}
