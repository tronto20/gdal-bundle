@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:JsModule("@gdal4k/web-engine")
package dev.gdal4k
import kotlin.js.JsAny
internal external fun createGdalWorker(): JsAny
