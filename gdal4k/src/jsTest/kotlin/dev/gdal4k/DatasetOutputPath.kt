package dev.gdal4k
internal actual fun datasetOutputPath(name: String): String {
    val base: String = js("typeof process !== 'undefined' && process.versions && process.versions.node ? process.getBuiltinModule('os').tmpdir() + '/' : 'opfs:/'")
    return base + datasetOutputPrefix + "-" + name
}
