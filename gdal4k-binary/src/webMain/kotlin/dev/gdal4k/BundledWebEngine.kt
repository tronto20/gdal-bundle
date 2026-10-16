package dev.gdal4k

// Payloads belong to JS/wasmJs publications. Shared metadata must not build a web engine.
internal expect fun bundledEngineUrl(): String
internal expect val gdalInflateSource: String
