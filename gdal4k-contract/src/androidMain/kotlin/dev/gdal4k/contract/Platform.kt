package dev.gdal4k.contract
import android.content.Context
import dev.gdal4k.GdalOptions
import dev.gdal4k.GdalAndroid
import kotlinx.coroutines.runBlocking
actual fun launch(block: suspend () -> Unit) = runBlocking { block() }
actual fun platformOptions() = GdalOptions()
object AndroidContract {
    @JvmStatic fun run(context: Context) = runBlocking { contract(GdalAndroid.options(context)) }
}
