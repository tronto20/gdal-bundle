package dev.gdal4k

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private fun make(source: String, url: String, success: (dynamic) -> Unit, failure: (String) -> Unit) { js("""const node = typeof process !== 'undefined' && process.versions && process.versions.node;
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
}, error => { closed = true; release(); worker.terminate(); failure(String(error.stack || error)); });""") }
private fun invoke(bridge: dynamic, request: String, success: (String) -> Unit, failure: (String) -> Unit) { js("bridge.call(request).then(success, e => failure(String(e.stack || e)))") }
private fun dispose(bridge: dynamic, success: () -> Unit, failure: (String) -> Unit) { js("bridge.close().then(success, e => failure(String(e.stack || e)))") }

internal actual suspend fun createTransport(options: GdalOptions): GdalTransport {
    val url = options.webBridgeUrl ?: bundledEngineUrl()
    val bridge: dynamic = suspendCoroutine { c ->
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
