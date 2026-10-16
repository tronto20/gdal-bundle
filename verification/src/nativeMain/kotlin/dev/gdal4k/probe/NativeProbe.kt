@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.gdal4k.probe

actual suspend fun runGdalProbe(): Int = dev.gdal4k.cprobe.gdal4k_probe()
