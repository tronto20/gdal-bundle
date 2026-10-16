package dev.gdal4k.contract
import dev.gdal4k.GdalOptions
import kotlinx.coroutines.runBlocking
actual fun launch(block: suspend () -> Unit) = runBlocking { block() }
actual fun platformOptions() = GdalOptions()
