// This package is extracted from the Maven KLIB by Kotlin's Gradle npm resolver.
// Static URLs let webpack emit the Worker and Wasm as separate application assets.
export function createGdalWorker() {
    const node = typeof process !== 'undefined' && process.versions?.node;
    const workerUrl = new URL('./gdal4k-worker.mjs', import.meta.url);
    const worker = node
        ? new (process.getBuiltinModule('worker_threads').Worker)(workerUrl, {type: 'module'})
        : new Worker(new URL('./gdal4k-worker.mjs', import.meta.url), {type: 'module'});
    const pending = new Map();
    let sequence = 0, closed = false;
    function fail(error) {
        closed = true;
        for (const task of pending.values()) task.reject(error);
        pending.clear();
        worker.terminate();
    }
    function receive(message) {
        const task = pending.get(message.id);
        if (!task) return;
        pending.delete(message.id);
        if (node && pending.size === 0) worker.unref();
        if (message.error) task.reject(new Error(message.error)); else task.resolve(message.result);
    }
    if (node) {
        worker.on('message', receive);
        worker.on('error', fail);
        worker.on('exit', code => { if (!closed) fail(new Error('GDAL Worker exited: ' + code)); });
    } else {
        worker.onmessage = event => receive(event.data);
        worker.onerror = event => fail(new Error(event.message || 'GDAL Worker failed'));
    }
    function send(operation, payload, file) {
        if (closed) return Promise.reject(new Error('GDAL Worker is closed'));
        if (node) worker.ref();
        return new Promise((resolve, reject) => {
            const id = ++sequence;
            pending.set(id, {resolve, reject});
            try { worker.postMessage({id, operation, payload, file}); }
            catch (error) { pending.delete(id); if (node && !pending.size) worker.unref(); reject(error); }
        });
    }
    return send('init').then(() => ({
        call: (request, file) => send('call', request, file),
        close: () => send('close').finally(() => { closed = true; return worker.terminate(); }),
    }), error => { fail(error); throw error; });
}
