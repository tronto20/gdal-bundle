package dev.gdal4k

internal actual suspend fun integrationOptions() = GdalOptions(
    libraryPath = System.getProperty("gdal4k.library"),
    projData = System.getenv("PROJ_DATA"),
    gdalData = System.getenv("GDAL_DATA"),
)
