package dev.gdal4k.binary
import dev.gdal4k.GdalOptions
internal actual fun bundledOptions(bundleDir: String?): GdalOptions {
    require(bundleDir == null) { "This platform embeds its runtime; a bundle directory override is only available on JVM/Android" }
    return GdalOptions()
}
