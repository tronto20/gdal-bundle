package dev.gdal4k

import android.content.Context

/** Compatibility entry point. PROJ and GDAL resources are embedded in the native library. */
object GdalAndroid {
    @Suppress("UNUSED_PARAMETER")
    suspend fun options(context: Context): GdalOptions = GdalOptions()
    suspend fun create(context: Context): GdalSession = Gdal.create(options(context))
}
