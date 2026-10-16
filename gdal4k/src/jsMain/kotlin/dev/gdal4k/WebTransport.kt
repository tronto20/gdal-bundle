package dev.gdal4k

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private fun make(url: String, assets: String, success: (dynamic) -> Unit, failure: (String) -> Unit) { js("import(url).then(m => m.createGdal4k(assets)).then(success, e => failure(String(e.stack || e)))") }
private fun invoke(bridge: dynamic, request: String, success: (String) -> Unit, failure: (String) -> Unit) { js("bridge.call(request).then(success, e => failure(String(e.stack || e)))") }
private fun dispose(bridge: dynamic, success: () -> Unit, failure: (String) -> Unit) { js("bridge.close().then(success, e => failure(String(e.stack || e)))") }

internal actual suspend fun createTransport(options: GdalOptions): GdalTransport {
    val url = requireNotNull(options.webBridgeUrl) { "Set webBridgeUrl to the hosted gdal4k-web.mjs URL" }
    val bridge: dynamic = suspendCoroutine { c ->
        make(url, options.webAssetsUrl.orEmpty(), { c.resume(it) }, { c.resumeWithException(GdalException(it)) })
    }
    return object : GdalTransport {
        override suspend fun call(request: String): String = suspendCoroutine { c ->
            invoke(bridge, request, { c.resume(it) }, { c.resumeWithException(GdalException(it)) })
        }
        override suspend fun close(): Unit = suspendCoroutine { c ->
            dispose(bridge, { c.resume(Unit) }, { c.resumeWithException(GdalException(it)) })
        }
    }
}
