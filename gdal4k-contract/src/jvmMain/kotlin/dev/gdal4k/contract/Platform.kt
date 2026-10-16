package dev.gdal4k.contract
import dev.gdal4k.GdalOptions
import kotlinx.coroutines.runBlocking
actual fun launch(block: suspend () -> Unit) = runBlocking { block() }
actual fun platformOptions() = GdalOptions(libraryPath = System.getProperty("gdal4k.library"), projData = System.getenv("PROJ_DATA"), gdalData = System.getenv("GDAL_DATA"))
