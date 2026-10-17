@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.gdal4k
import kotlinx.cinterop.toKString
internal actual fun datasetOutputPath(name: String): String = (platform.posix.getenv("TMPDIR")?.toKString() ?: "/tmp") + "/gdal4k-$name"
