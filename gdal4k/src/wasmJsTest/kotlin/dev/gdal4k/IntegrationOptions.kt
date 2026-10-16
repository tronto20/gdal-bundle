@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package dev.gdal4k

@JsFun("""(key) => typeof process !== 'undefined' && process.versions?.node
    ? process.env[key]
    : new URL(key === 'GDAL4K_BRIDGE_URL' ? '/gdal/gdal4k-web.mjs' : '/gdal/', location.href).href""")
private external fun testUrl(key: String): String
internal actual suspend fun integrationOptions() = GdalOptions(
    webBridgeUrl = testUrl("GDAL4K_BRIDGE_URL"),
    webAssetsUrl = testUrl("GDAL4K_ASSETS_URL"),
)
