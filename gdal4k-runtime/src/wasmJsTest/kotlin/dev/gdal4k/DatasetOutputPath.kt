@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package dev.gdal4k
@JsFun("() => typeof process !== 'undefined' && process.versions?.node ? process.getBuiltinModule('os').tmpdir() + '/' : 'opfs:/'")
private external fun outputBase(): String
internal actual fun datasetOutputPath(name: String): String = outputBase() + datasetOutputPrefix + "-" + name
