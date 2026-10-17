package dev.gdal4k.runtime

import dev.gdal4k.GdalFiles

/** Polygonize options as strings; all output/band settings are named arguments. */
suspend fun Dataset.polygonize(
    vararg arguments: String, driver: String = "GPKG", name: String = "polygons.gpkg",
    band: Int = 1, mask: Band? = null, layerName: String = "polygons", fieldName: String = "value",
): GdalFiles = polygonize(driver, name, band, mask, layerName, fieldName, arguments.asList())

suspend fun Dataset.polygonizeTo(
    path: String, vararg arguments: String, driver: String = "GPKG",
    band: Int = 1, mask: Band? = null, layerName: String = "polygons", fieldName: String = "value",
) = polygonizeTo(path, driver, band, mask, layerName, fieldName, arguments.asList())
