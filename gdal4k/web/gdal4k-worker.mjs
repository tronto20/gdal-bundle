// Low-level calls use the real GDAL exported by the pinned gdal3.js 2.8.1 Wasm build.
// We own every allocation, GDAL handle and virtual file created by this adapter.
const node = typeof process !== 'undefined' && process.versions?.node;
let port, send;
if (node) {
  ({ parentPort: port } = await import('node:worker_threads'));
  send = message => port.postMessage(message);
} else {
  port = globalThis;
  send = message => postMessage(message);
}
let module, api, sequence = 0;
const datasets = new Map();
const encoder = new TextEncoder();
const decoder = new TextDecoder();
const bindings = new Map();
const fn = (name, result, params = []) => {
  if (!bindings.has(name)) bindings.set(name, module.cwrap(name, result, params));
  return bindings.get(name);
};
const call = (name, result, params, ...args) => fn(name, result, params)(...args);
const error = () => call('CPLGetLastErrorMsg', 'string', []);
const ensure = (condition, message) => { if (!condition) throw new Error(`${message}: ${error()}`); };
const checkError = () => ensure(call('CPLGetLastErrorType', 'number', []) < 3, 'GDAL operation failed');
const nameCheck = name => {
  if (!name || name === '.' || name === '..' || /[\/\\:\0]/.test(name)) throw new Error('Invalid file basename');
};
function options(values) {
  const strings = [];
  let pointer = 0;
  try {
    for (const value of values) {
      if (value.includes('\0')) throw new Error('NUL in GDAL argument');
      const bytes = encoder.encode(value);
      const p = module._malloc(bytes.length + 1); ensure(p, 'Allocation failed');
      strings.push(p); module.HEAPU8.set(bytes, p); module.HEAPU8[p + bytes.length] = 0;
    }
    pointer = module._malloc((strings.length + 1) * 4); ensure(pointer, 'Allocation failed');
    module.HEAPU32.set([...strings, 0], pointer / 4);
    return { pointer, close: () => { strings.forEach(p => module._free(p)); module._free(pointer); } };
  } catch (e) { strings.forEach(p => module._free(p)); if (pointer) module._free(pointer); throw e; }
}
function remove(directory) {
  for (const name of module.FS.readdir(directory)) {
    if (name === '.' || name === '..') continue;
    const path = `${directory}/${name}`;
    if (module.FS.isDir(module.FS.stat(path).mode)) remove(path); else module.FS.unlink(path);
  }
  module.FS.rmdir(directory);
}
function directory() {
  const path = `/gdal4k-${++sequence}`;
  module.FS.mkdir(path);
  return path;
}
function base64(bytes) {
  if (node) return Buffer.from(bytes).toString('base64');
  let text = '';
  for (let i = 0; i < bytes.length; i += 8192) text += String.fromCharCode(...bytes.subarray(i, i + 8192));
  return btoa(text);
}
function unbase64(value) {
  return node ? new Uint8Array(Buffer.from(value, 'base64')) : Uint8Array.from(atob(value), c => c.charCodeAt(0));
}
function resultFiles(dir, main) {
  const files = module.FS.readdir(dir).filter(n => n !== '.' && n !== '..').map(name => {
    nameCheck(name);
    return { name, data: base64(module.FS.readFile(`${dir}/${name}`)) };
  });
  ensure(files.some(file => file.name === main), 'Main output file was not created');
  return { main, files };
}
function closeDataset(id) {
  const ds = datasets.get(id);
  if (!ds) throw new Error('Dataset is closed or belongs to another session');
  call('GDALClose', null, ['number'], ds.pointer);
  datasets.delete(id);
  remove(ds.directory);
}
function execute({ op, args }) {
  call('CPLErrorReset', null, []);
  if (op === 'drivers') {
    const result = [];
    const count = call('GDALGetDriverCount', 'number', []);
    for (let i = 0; i < count; i++) {
      const d = call('GDALGetDriver', 'number', ['number'], i);
      result.push({
        name: call('GDALGetDriverShortName', 'string', ['number'], d),
        longName: call('GDALGetDriverLongName', 'string', ['number'], d),
        raster: !!call('GDALGetMetadataItem', 'string', ['number', 'string', 'string'], d, 'DCAP_RASTER', null),
        vector: !!call('GDALGetMetadataItem', 'string', ['number', 'string', 'string'], d, 'DCAP_VECTOR', null),
      });
    }
    return result;
  }
  if (op === 'transform') {
    let source = 0, target = 0, transform = 0, coordinates = 0;
    try {
      source = call('OSRNewSpatialReference', 'number', ['number'], 0);
      target = call('OSRNewSpatialReference', 'number', ['number'], 0);
      ensure(call('OSRSetFromUserInput', 'number', ['number', 'string'], source, args.source) === 0, 'Invalid source CRS');
      ensure(call('OSRSetFromUserInput', 'number', ['number', 'string'], target, args.target) === 0, 'Invalid target CRS');
      for (const srs of [source, target]) call('OSRSetAxisMappingStrategy', null, ['number', 'number'], srs, 0);
      transform = call('OCTNewCoordinateTransformation', 'number', ['number', 'number'], source, target);
      ensure(transform, 'Cannot create coordinate transformation');
      coordinates = module._malloc(24); ensure(coordinates, 'Allocation failed');
      return args.points.map(point => {
        module.HEAPF64.set(point, coordinates / 8);
        const ok = call('OCTTransform', 'number', ['number', 'number', 'number', 'number', 'number'], transform, 1, coordinates, coordinates + 8, coordinates + 16);
        const result = Array.from(module.HEAPF64.subarray(coordinates / 8, coordinates / 8 + 3));
        ensure(ok && result.every(Number.isFinite), 'Coordinate transformation failed');
        return result;
      });
    } finally {
      if (coordinates) module._free(coordinates);
      if (transform) call('OCTDestroyCoordinateTransformation', null, ['number'], transform);
      if (source) call('OSRDestroySpatialReference', null, ['number'], source);
      if (target) call('OSRDestroySpatialReference', null, ['number'], target);
    }
  }
  if (op === 'open') {
    const dir = directory();
    let pointer = 0;
    const opts = options(args.options);
    try {
      nameCheck(args.main);
      for (const file of args.files) { nameCheck(file.name); module.FS.writeFile(`${dir}/${file.name}`, unbase64(file.data)); }
      pointer = call('GDALOpenEx', 'number', ['string', 'number', 'number', 'number', 'number'], `${dir}/${args.main}`, 64, 0, opts.pointer, 0);
      ensure(pointer, 'Cannot open dataset');
      const id = String(++sequence);
      const result = {
        id,
        driver: call('GDALGetDriverShortName', 'string', ['number'], call('GDALGetDatasetDriver', 'number', ['number'], pointer)),
        width: call('GDALGetRasterXSize', 'number', ['number'], pointer),
        height: call('GDALGetRasterYSize', 'number', ['number'], pointer),
        bands: call('GDALGetRasterCount', 'number', ['number'], pointer),
        layers: call('GDALDatasetGetLayerCount', 'number', ['number'], pointer),
      };
      datasets.set(id, { pointer, directory: dir }); pointer = 0;
      return result;
    } catch (e) { if (pointer) call('GDALClose', null, ['number'], pointer); remove(dir); throw e; }
    finally { opts.close(); }
  }
  const dataset = datasets.get(args.id);
  if (!dataset) throw new Error('Dataset is closed or belongs to another session');
  if (op === 'close') { closeDataset(args.id); return {}; }
  if (op === 'info') {
    const raster = call('GDALGetRasterCount', 'number', ['number'], dataset.pointer) > 0;
    const opts = options(['-json', ...(raster ? [] : ['-so', '-al']), ...args.options]);
    let pointer = 0;
    const prefix = raster ? 'GDALInfo' : 'GDALVectorInfo';
    try {
      pointer = call(`${prefix}OptionsNew`, 'number', ['number', 'number'], opts.pointer, 0);
      ensure(pointer, 'Invalid info options');
      // cwrap(string) does not release the returned CPL allocation.
      const text = call(prefix, 'number', ['number', 'number'], dataset.pointer, pointer);
      ensure(text, 'Cannot inspect dataset');
      try { checkError(); return JSON.parse(module.UTF8ToString(text)); } finally { module._free(text); }
    } finally { if (pointer) call(`${prefix}OptionsFree`, null, ['number'], pointer); opts.close(); }
  }
  ensure(['translate', 'warp', 'vector'].includes(op), 'Unknown GDAL operation');
  const dir = directory();
  const opts = options([...args.options, op === 'vector' ? '-f' : '-of', args.driver]);
  const prefix = op === 'translate' ? 'GDALTranslate' : op === 'warp' ? 'GDALWarpApp' : 'GDALVectorTranslate';
  let pointer = 0, output = 0, list = 0;
  try {
    nameCheck(args.name);
    pointer = call(`${prefix}OptionsNew`, 'number', ['number', 'number'], opts.pointer, 0);
    ensure(pointer, 'Invalid conversion options');
    const path = `${dir}/${args.name}`;
    if (op === 'translate') {
      output = call('GDALTranslate', 'number', ['string', 'number', 'number', 'number'], path, dataset.pointer, pointer, 0);
    } else {
      list = module._malloc(4); ensure(list, 'Allocation failed'); module.HEAPU32[list / 4] = dataset.pointer;
      output = call(op === 'warp' ? 'GDALWarp' : 'GDALVectorTranslate', 'number', ['string', 'number', 'number', 'number', 'number', 'number'], path, 0, 1, list, pointer, 0);
    }
    ensure(output, 'GDAL conversion failed');
    call('GDALClose', null, ['number'], output); output = 0;
    checkError();
    return resultFiles(dir, args.name);
  } finally {
    if (output) call('GDALClose', null, ['number'], output);
    if (pointer) call(`${prefix}OptionsFree`, null, ['number'], pointer);
    if (list) module._free(list);
    opts.close(); remove(dir);
  }
}
async function initialize(assets) {
  if (module) return;
  if (node) {
    const { createRequire } = await import('node:module');
    const { fileURLToPath } = await import('node:url');
    const require = createRequire(import.meta.url);
    // Explicit path is useful when assets are deployed independently of the Kotlin package.
    const init = require(assets ? fileURLToPath(new URL('gdal3.node.js', assets)) : 'gdal3.js/node');
    const path = await import('node:path');
    api = await init(assets ? { path: path.relative(process.cwd(), fileURLToPath(new URL(assets))) + '/' } : {});
  } else {
    if (!assets) throw new Error('webAssetsUrl must point to the gdal3.js 2.8.1 asset directory');
    // UMD sets self.initGdalJs. Module workers have no importScripts.
    await import(new URL('gdal3.js', assets).href);
    api = await globalThis.initGdalJs({ path: assets, useWorker: false });
  }
  if (!node) port.onmessage = event => onMessage(event.data);
  module = api.Module;
  ensure(module?.FS, 'GDAL Wasm module did not initialize');
}
let queue = Promise.resolve();
const onMessage = message => {
  queue = queue.then(async () => {
    try {
      if (message.op === 'initialize') { await initialize(message.assets); send({ id: message.id, result: null }); }
      else if (message.op === 'close') { for (const id of [...datasets.keys()]) closeDataset(id); send({ id: message.id, result: null }); }
      else {
        let response;
        try { response = { result: execute(JSON.parse(message.request)) }; }
        catch (e) { response = { error: String(e.message || e) }; }
        send({ id: message.id, result: JSON.stringify(response) });
      }
    } catch (e) { send({ id: message.id, error: String(e.stack || e) }); }
  });
};
if (node) port.on('message', onMessage); else port.onmessage = event => onMessage(event.data);
