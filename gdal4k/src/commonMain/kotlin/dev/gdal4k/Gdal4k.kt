package dev.gdal4k

import dev.gdal4k.binary.Gdal4kBinary
import dev.gdal4k.runtime.GdalRuntime

/** Default entry point for the bundled GDAL runtime on every supported platform. */
object Gdal4k {
    suspend fun prepare() = Gdal4kBinary.prepare()
    fun runtime(): GdalRuntime = Gdal4kBinary.runtime()
    suspend fun close() = Gdal4kBinary.close()
}
