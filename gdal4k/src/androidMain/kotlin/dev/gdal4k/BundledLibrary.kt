package dev.gdal4k
internal actual fun loadBundledLibrary(path: String?) {
    if (path == null) System.loadLibrary("gdal4k") else System.load(path)
}
