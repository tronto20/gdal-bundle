package dev.gdal4k

private fun testUrl(environmentKey: String): String = js("""((key) => typeof process !== 'undefined' && process.versions && process.versions.node
    ? process.env[key]
    : new URL(key === 'GDAL4K_BRIDGE_URL' ? '/gdal/gdal4k-web.mjs' : '/gdal/', location.href).href)(environmentKey)""")
internal actual suspend fun integrationOptions() = GdalOptions(
    webBridgeUrl = testUrl("GDAL4K_BRIDGE_URL"),
    webAssetsUrl = testUrl("GDAL4K_ASSETS_URL"),
)
