package dev.gdal4k.runtime
actual object GdalPlatform { actual fun current(): GdalPlatformInfo = GdalPlatformInfo("js", "web", "wasm32") }
