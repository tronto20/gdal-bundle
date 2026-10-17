@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package dev.gdal4k

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.*

@JsFun("""(source, url, success, failure) => { const node = typeof process !== 'undefined' && process.versions && process.versions.node;
let objectUrl;
const worker = node
    ? new (process.getBuiltinModule('worker_threads').Worker)(new URL('data:text/javascript;charset=utf-8,' + encodeURIComponent(source)), {type:'module'})
    : new Worker(objectUrl = URL.createObjectURL(new Blob([source], {type:'text/javascript'})), {type:'module'});
const pending = new Map(); let sequence = 0, closed = false;
const receive = message => {
    const task = pending.get(message.id); if (!task) return;
    pending.delete(message.id);
    if (node && pending.size === 0) worker.unref();
    if (message.error) task.reject(new Error(message.error)); else task.resolve(message.result);
};
const fail = error => { closed = true; for (const task of pending.values()) task.reject(error); pending.clear(); worker.terminate(); };
if (node) {
    worker.on('message', receive); worker.on('error', fail);
    worker.on('exit', code => { if (!closed) fail(new Error('GDAL Worker exited: ' + code)); });
} else {
    worker.onmessage = event => receive(event.data);
    worker.onerror = event => fail(new Error(event.message || 'GDAL Worker failed'));
}
const send = (operation, payload) => new Promise((resolve, reject) => {
    if (closed) return reject(new Error('GDAL Worker is closed'));
    if (node) worker.ref();
    const id = ++sequence; pending.set(id, {resolve,reject});
    try { worker.postMessage({id,operation,payload}); } catch(error) { pending.delete(id); reject(error); }
});
function release() { if (objectUrl) { URL.revokeObjectURL(objectUrl); objectUrl = undefined; } }
send('init', url).then(() => {
    release();
    success({
        call: request => send('call', request),
        close: () => send('close').finally(() => { closed = true; return worker.terminate(); })
    });
}, error => { closed = true; release(); worker.terminate(); failure(String(error.stack || error)); }); }""")
private external fun make(source: String, url: String, success: (JsAny) -> Unit, failure: (String) -> Unit)
@JsFun("(bridge, request, success, failure) => bridge.call(request).then(success, e => failure(String(e.stack || e)))")
private external fun invoke(bridge: JsAny, request: String, success: (String) -> Unit, failure: (String) -> Unit)
@JsFun("(bridge, success, failure) => bridge.close().then(success, e => failure(String(e.stack || e)))")
private external fun dispose(bridge: JsAny, success: () -> Unit, failure: (String) -> Unit)

internal actual suspend fun createTransport(options: GdalOptions): GdalTransport {
    val url = options.webBridgeUrl ?: bundledEngineUrl()
    val bridge: JsAny = suspendCoroutine { c ->
        make(gdalWorkerSource, url, { c.resume(it) }, { c.resumeWithException(GdalException(it)) })
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
