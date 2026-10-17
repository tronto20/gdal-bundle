package dev.gdal4k

import android.content.ContentResolver
import android.content.Context
import androidx.startup.Initializer

/** Registers only the application resolver. This does not load GDAL or open any URI. */
class AndroidResolverInitializer : Initializer<ContentResolver> {
    override fun create(context: Context): ContentResolver =
        context.applicationContext.contentResolver.also { AndroidResolvers.default = it }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

internal object AndroidResolvers {
    @Volatile var default: ContentResolver? = null

    fun requireDefault(): ContentResolver = checkNotNull(default) {
        "The GDAL Android resolver was not registered. Enable AndroidResolverInitializer " +
            "or pass resolver explicitly to openDataset(uri, resolver = ...)."
    }
}
