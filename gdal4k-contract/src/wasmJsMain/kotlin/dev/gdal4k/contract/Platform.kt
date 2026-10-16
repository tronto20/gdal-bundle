@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package dev.gdal4k.contract
import dev.gdal4k.GdalOptions
import kotlin.coroutines.*
@JsFun("() => globalThis.GDAL4K_BRIDGE_URL")
private external fun bridgeUrl(): String
@JsFun("() => globalThis.GDAL4K_ASSETS_URL")
private external fun assetsUrl(): String
actual fun platformOptions() = GdalOptions(webBridgeUrl = bridgeUrl(), webAssetsUrl = assetsUrl())
actual fun launch(block: suspend () -> Unit) {
    block.startCoroutine(object : Continuation<Unit> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<Unit>) { result.getOrThrow() }
    })
}
