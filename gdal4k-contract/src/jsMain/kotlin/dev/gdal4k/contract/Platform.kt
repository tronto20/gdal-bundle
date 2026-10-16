package dev.gdal4k.contract
import dev.gdal4k.GdalOptions
import kotlin.coroutines.*
private fun bridgeUrl(): String = js("globalThis.GDAL4K_BRIDGE_URL")
private fun assetsUrl(): String = js("globalThis.GDAL4K_ASSETS_URL")
actual fun platformOptions() = GdalOptions(webBridgeUrl = bridgeUrl(), webAssetsUrl = assetsUrl())
actual fun launch(block: suspend () -> Unit) {
    block.startCoroutine(object : Continuation<Unit> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<Unit>) { result.getOrThrow() }
    })
}
