package dev.gdal4k
internal actual fun datasetOutputPath(name: String): String = java.io.File(System.getProperty("java.io.tmpdir"),"gdal4k-$name").absolutePath
