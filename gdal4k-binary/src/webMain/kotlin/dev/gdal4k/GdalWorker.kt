package dev.gdal4k

// The Worker owns the Wasm instance and every native pointer. Only JSON and opaque IDs cross the boundary.
internal val gdalWorkerSource = "const inflate = (function() { var module = {exports:{}};\n" +
    gdalInflateSource + "\nreturn module.exports; })();\n" + """
const node = typeof process !== 'undefined' && process.versions && process.versions.node;
const port = node ? process.getBuiltinModule('worker_threads').parentPort : globalThis;
const send = message => port.postMessage(message);
let module, context, queue = Promise.resolve();
async function dispatch(message) {
    const {id, operation, payload} = message;
    try {
        let result = null;
        if (operation === 'init') {
            let url = payload, objectUrl;
            if (payload.indexOf('data:application/x-gdal4k-deflate;size=') === 0) {
                const comma = payload.indexOf(',');
                const size = Number(payload.substring(payload.indexOf('size=') + 5, payload.indexOf(';base64,')));
                const binary = atob(payload.substring(comma + 1));
                const compressed = new Uint8Array(binary.length);
                for (let i = 0; i < binary.length; i++) compressed[i] = binary.charCodeAt(i);
                const decoded = inflate(compressed, new Uint8Array(size));
                if (decoded.length !== size) throw new Error('Invalid embedded GDAL engine length');
                url = node ? 'data:text/javascript;charset=utf-8,' + encodeURIComponent(new TextDecoder().decode(decoded))
                    : (objectUrl = URL.createObjectURL(new Blob([decoded], {type:'text/javascript'})));
            }
            try { module = await (await import(url)).default(); }
            finally { if (objectUrl) URL.revokeObjectURL(objectUrl); }
            context = module.ccall('gdal4k_create', 'number', ['string','string'], ['', '']);
            if (!context) throw new Error('Unable to initialize bundled GDAL');
        } else if (operation === 'call') {
            if (!context) throw new Error('GDAL Worker is closed');
            const pointer = module.ccall('gdal4k_execute', 'number', ['number','string'], [context,payload]);
            if (!pointer) throw new Error('GDAL allocation failed');
            try { result = module.UTF8ToString(pointer); } finally { module._gdal4k_free(pointer); }
        } else if (operation === 'close') {
            if (context) module._gdal4k_destroy(context);
            context = 0;
        } else throw new Error('Unknown GDAL Worker operation');
        send({id, result});
    } catch (error) { send({id, error: String(error.stack || error)}); }
}
const receive = message => { queue = queue.then(() => dispatch(message)); };
if (node) port.on('message', receive);
else port.onmessage = event => receive(event.data);
"""
