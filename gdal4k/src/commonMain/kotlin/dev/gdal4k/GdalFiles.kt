package dev.gdal4k

/** A named byte buffer. This is data, not a separate dataset or engine handle. */
data class GdalFile(val name: String, val bytes: ByteArray) {
    init { requireFileName(name) }
}

/** A main file and its sidecars, such as a Shapefile's .shp/.dbf/.shx files. */
data class GdalFiles(val main: String, val files: List<GdalFile>) {
    init {
        requireFileName(main)
        require(files.isNotEmpty() && files.map { it.name }.distinct().size == files.size) { "File names must be unique" }
        require(files.any { it.name == main }) { "Missing main file: $main" }
    }
    constructor(file: GdalFile) : this(file.name, listOf(file))
    fun mainFile(): GdalFile = files.first { it.name == main }
}

data class GdalPoint(val x: Double, val y: Double, val z: Double = 0.0)
data class RasterWindow(val x: Int, val y: Int, val width: Int, val height: Int) {
    init { require(x >= 0 && y >= 0 && width > 0 && height > 0) }
}
data class GdalDriver(val name: String, val longName: String, val raster: Boolean, val vector: Boolean)
class GdalException(message: String, cause: Throwable? = null) : Exception(message, cause)

private fun requireFileName(name: String) {
    require(name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' || it == ':' }) { "Expected a file basename: $name" }
}
