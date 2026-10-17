package dev.gdal4k.runtime

import dev.gdal4k.*

suspend fun GdalRuntime.openDataset(file: GdalFile, options: DatasetOpenOptions = DatasetOpenOptions()): Dataset =
    openDataset(GdalFiles(file), options)

suspend fun GdalRuntime.openDataset(name: String, bytes: ByteArray, options: DatasetOpenOptions = DatasetOpenOptions()): Dataset =
    openDataset(GdalFile(name, bytes), options)

/** Reads raw values without applying scale/offset or replacing NoData. Band indices are 1-based. */
suspend fun Dataset.readRaster(band: Int = 1, window: RasterWindow? = null): DoubleArray {
    require(band in 1..getRasterCount()) { "Band indices are 1-based" }
    val width = getRasterXSize()
    val height = getRasterYSize()
    val region = window ?: RasterWindow(0, 0, width, height)
    require(region.x.toLong() + region.width <= width && region.y.toLong() + region.height <= height) { "Window is outside the raster" }
    val count = region.width.toLong() * region.height
    require(count <= Int.MAX_VALUE) { "Raster buffer is too large; read smaller windows" }
    return requireNotNull(getRasterBand(band)).use { raster ->
        DoubleArray(count.toInt()).also { buffer ->
            check(raster.readRaster(region.x, region.y, region.width, region.height, buffer) == 0) { "GDAL raster read failed" }
        }
    }
}

/** Uses traditional GIS axis order: longitude/easting first, latitude/northing second. */
suspend fun GdalRuntime.transform(source: String, target: String, points: List<GdalPoint>): List<GdalPoint> {
    require(points.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }) { "Coordinates must be finite" }
    if (points.isEmpty()) return emptyList()
    return createSpatialReference(source).use { from ->
        createSpatialReference(target).use { to ->
            from.setAxisMappingStrategy(0) // OAMS_TRADITIONAL_GIS_ORDER
            to.setAxisMappingStrategy(0)
            requireNotNull(createCoordinateTransformation(from, to)).use { transformation ->
                points.map { point ->
                    val p = transformation.transformPoint(point.x, point.y, point.z)
                    check(p.all { it.isFinite() }) { "Coordinate transformation failed" }
                    GdalPoint(p[0], p[1], p[2])
                }
            }
        }
    }
}
