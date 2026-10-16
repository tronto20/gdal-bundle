@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package dev.gdal4k

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.*

@JsFun("(url, assets, success, failure) => { import(url).then(m => m.createGdal4k(assets)).then(success, e => failure(String(e.stack || e))); }")
private external fun make(url: String, assets: String, success: (JsAny) -> Unit, failure: (String) -> Unit)
@JsFun("(bridge, request, success, failure) => { bridge.call(request).then(success, e => failure(String(e.stack || e))); }")
private external fun invoke(bridge: JsAny, request: String, success: (String) -> Unit, failure: (String) -> Unit)
@JsFun("(bridge, success, failure) => { bridge.close().then(success, e => failure(String(e.stack || e))); }")
private external fun dispose(bridge: JsAny, success: () -> Unit, failure: (String) -> Unit)

internal actual suspend fun createTransport(options: GdalOptions): GdalTransport {
    val url = requireNotNull(options.webBridgeUrl) { "Set webBridgeUrl to the hosted gdal4k-web.mjs URL" }
    val bridge: JsAny = suspendCoroutine { c ->
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
