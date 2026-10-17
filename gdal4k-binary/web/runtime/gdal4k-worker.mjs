import createModule from './gdal4k.mjs';
import {Inputs} from './gdal4k-inputs.mjs';

const node = typeof process !== 'undefined' && process.versions?.node;
const port = node ? process.getBuiltinModule('worker_threads').parentPort : globalThis;
const wasmUrl = new URL('./gdal4k.wasm', import.meta.url);
let module, context, inputs, queue = Promise.resolve();
const datasets = new Map();
async function execute(request) {
    const pointer = await module.ccall('gdal4k_execute', 'number', ['number', 'string'], [context, JSON.stringify(request)], {async: true});
    if (!pointer) throw new Error('GDAL allocation failed');
    try { return module.UTF8ToString(pointer); } finally { module._gdal4k_free(pointer); }
}
async function dispatch({id, operation, payload, file}) {
    let mounted;
    try {
        let result = null;
        if (operation === 'init') {
            const options = node
                ? {wasmBinary: process.getBuiltinModule('fs').readFileSync(wasmUrl)}
                : {locateFile: () => wasmUrl.href};
            module = await createModule(options);
            delete options.wasmBinary;
            inputs = new Inputs();
            module.gdal4kInputs = inputs;
            context = await module.ccall('gdal4k_create', 'number', ['string', 'string'], ['', ''], {async: true});
            if (!context) throw new Error('Unable to initialize bundled GDAL');
        } else if (operation === 'call') {
            if (!context) throw new Error('GDAL Worker is closed');
            const request = JSON.parse(payload);
            const args = request.args;
            if (args?.type === 'GdalRuntime' && args.method === 'openDataset_String_DatasetOpenOptions') {
                const source = args.parameters.source;
                const virtual = /^\/vsi[a-z0-9_]+\//i.test(source);
                if (file || /^https?:\/\//i.test(source) || (node && !virtual)) {
                    if (args.parameters.options?.update) throw new Error('Direct web inputs are read-only');
                    mounted = await inputs.mount(source, file);
                    args.parameters.source = mounted.path;
                } else if (!virtual) {
                    throw new Error('Browser paths require a selected File; use openDataset(file) or an HTTP(S) URL');
                }
            } else if (file) throw new Error('A browser File can only be attached to openDataset');
            inputs.lastError = null;
            result = await execute(request);
            const response = JSON.parse(result);
            if (inputs.lastError) {
                // RasterIO can return a numeric error; keep the transport error actionable.
                const failure = inputs.lastError;
                if (mounted && typeof response.result?.value === 'string') {
                    await execute({op: 'runtime', args: {type: 'Handle', handle: response.result.value, method: 'close', parameters: {}}});
                }
                throw failure;
            }
            if (mounted) {
                if (response.error) { mounted.close(); mounted = null; }
                else { datasets.set(response.result.value, mounted); mounted = null; }
            }
            if (!response.error && args?.method === 'close') {
                datasets.get(args.handle)?.close();
                datasets.delete(args.handle);
            }
        } else if (operation === 'close') {
            if (context) await module.ccall('gdal4k_destroy', null, ['number'], [context], {async: true});
            context = 0;
            for (const input of datasets.values()) input.close();
            datasets.clear();
        } else throw new Error('Unknown GDAL Worker operation');
        port.postMessage({id, result});
    } catch (error) {
        mounted?.close();
        port.postMessage({id, error: String(error.stack || error)});
    }
}
const receive = message => { queue = queue.then(() => dispatch(message)); };
if (node) port.on('message', receive); else port.onmessage = event => receive(event.data);
