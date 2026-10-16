import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import path from 'node:path';
import { runProbe } from './probe.mjs';

const require = createRequire(import.meta.url);
const initGdalJs = require('gdal3.js/node');
const gdal = await initGdalJs();
globalThis.gdal4kBeginProbe = (success, failure) => {
  runProbe(gdal, path.resolve('fixtures/sample.tif'), path.resolve('fixtures/sample.geojson')).then(success, error => failure(String(error.stack ?? error)));
};
await import(pathToFileURL(path.resolve(process.argv[2])).href);
