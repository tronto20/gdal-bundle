import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
const root = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
globalThis.GDAL4K_BRIDGE_URL = pathToFileURL(path.join(root, 'gdal4k/web/gdal4k-web.mjs')).href;
globalThis.GDAL4K_ASSETS_URL = pathToFileURL(path.join(root, 'verification/node_modules/gdal3.js/dist/package/')).href;
let succeeded = false;
const log = console.log;
console.log = (...args) => { if (args[0] === 'GDAL4K_LIBRARY_CONTRACT_OK') succeeded = true; log(...args); };
const timer = setTimeout(() => { console.error('Contract timed out'); process.exit(1); }, 90_000);
process.on('beforeExit', () => { if (!succeeded) process.exitCode = 1; });
const target = process.argv[2];
try {
  await import(pathToFileURL(path.join(root, `gdal4k-contract/build/compileSync/${target}/main/developmentExecutable/kotlin/gdal-bundle-gdal4k-contract.${target === 'wasmJs' ? 'mjs' : 'js'}`)).href);
  // A completed session terminates its worker, allowing Node to exit.
  timer.unref();
} catch (error) { clearTimeout(timer); throw error; }
