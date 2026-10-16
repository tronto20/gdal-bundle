import { chromium } from '../verification/node_modules/playwright/index.mjs';
import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const server = http.createServer(async (request, response) => {
  try {
    const relative = decodeURIComponent(new URL(request.url, 'http://localhost').pathname).slice(1);
    const file = path.resolve(root, relative);
    if (relative && !file.startsWith(root + path.sep)) { response.writeHead(403).end(); return; }
    const types = { '.js': 'text/javascript', '.mjs': 'text/javascript', '.wasm': 'application/wasm', '.html': 'text/html' };
    response.setHeader('Content-Type', types[path.extname(file)] ?? 'application/octet-stream');
    if (relative === '') { response.setHeader('Content-Type', 'text/html'); response.end('<!doctype html><title>gdal4k consumer contract</title>'); return; }
    response.end(await fs.readFile(file));
  } catch { response.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch({ headless: true });
try {
  for (const target of ['js', 'wasmJs']) {
    const page = await browser.newPage();
    const errors = [];
    let succeeded = false;
    page.on('pageerror', error => errors.push(String(error.stack ?? error)));
    page.on('console', message => {
      console.log(`[${target}] ${message.text()}`);
      if (message.text() === 'GDAL4K_LIBRARY_CONTRACT_OK') succeeded = true;
    });
    await page.goto(`${base}/`);
    await page.evaluate(async ({ target, base }) => {
      globalThis.GDAL4K_BRIDGE_URL = `${base}/gdal4k/web/gdal4k-web.mjs`;
      globalThis.GDAL4K_ASSETS_URL = `${base}/verification/node_modules/gdal3.js/dist/package/`;
      const prefix = `/gdal4k-contract/build/compileSync/${target}/main/developmentExecutable/kotlin/`;
      if (target === 'wasmJs') await import(`${prefix}gdal-bundle-gdal4k-contract.mjs`);
      else {
        const scripts = ['kotlin-kotlin-stdlib', 'kotlinx-atomicfu', 'kotlinx-coroutines-core', 'kotlinx-serialization-kotlinx-serialization-core', 'kotlinx-serialization-kotlinx-serialization-json', 'gdal-bundle-gdal4k', 'gdal-bundle-gdal4k-contract'];
        for (const name of scripts) await new Promise((resolve, reject) => {
          const script = document.createElement('script'); script.src = `${prefix}${name}.js`;
          script.onload = resolve; script.onerror = reject; document.head.append(script);
        });
      }
    }, { target, base });
    const deadline = Date.now() + 90_000;
    while (!succeeded && errors.length === 0 && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 100));
    await page.close();
    if (!succeeded || errors.length) throw new Error(`${target}: ${errors.join('; ') || 'contract timed out'}`);
  }
} finally { await browser.close(); server.close(); }
