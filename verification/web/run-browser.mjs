import { chromium } from 'playwright';
import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';

const root = process.cwd();
const server = http.createServer(async (request, response) => {
  try {
    const relative = decodeURIComponent(new URL(request.url, 'http://localhost').pathname).slice(1);
    const file = path.resolve(root, relative);
    if (!file.startsWith(root + path.sep)) { response.writeHead(403).end(); return; }
    const types = { '.js': 'text/javascript', '.mjs': 'text/javascript', '.wasm': 'application/wasm', '.html': 'text/html' };
    response.setHeader('Content-Type', types[path.extname(file)] ?? 'application/octet-stream');
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
    page.on('pageerror', error => errors.push(String(error)));
    page.on('console', message => {
      console.log(`[${target}] ${message.text()}`);
      if (message.text() === 'GDAL4K_PROBE_OK') succeeded = true;
    });
    await page.goto(`${base}/web/browser.html`);
    await page.evaluate(async target => {
      const gdal = await window.initGdalJs({ path: '/node_modules/gdal3.js/dist/package/', useWorker: true });
      const { runProbe } = await import('/web/probe.mjs');
      const tif = new File([await (await fetch('/fixtures/sample.tif')).arrayBuffer()], 'sample.tif');
      const json = new File([await (await fetch('/fixtures/sample.geojson')).arrayBuffer()], 'sample.geojson');
      globalThis.gdal4kBeginProbe = (success, failure) => runProbe(gdal, tif, json).then(success, error => failure(String(error.stack ?? error)));
      if (target === 'wasmJs') {
        await import('/build/compileSync/wasmJs/main/developmentExecutable/kotlin/gdal4k-platform-probe.mjs');
      } else {
        const load = src => new Promise((resolve, reject) => {
          const script = document.createElement('script'); script.src = src;
          script.onload = resolve; script.onerror = reject; document.head.append(script);
        });
        await load('/build/compileSync/js/main/developmentExecutable/kotlin/kotlin-kotlin-stdlib.js');
        await load('/build/compileSync/js/main/developmentExecutable/kotlin/gdal4k-platform-probe.js');
      }
    }, target);
    const deadline = Date.now() + 60_000;
    while (!succeeded && errors.length === 0 && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 100));
    await page.close();
    if (!succeeded || errors.length) throw new Error(`${target}: ${errors.join('; ') || 'probe timed out'}`);
  }
} finally { await browser.close(); server.close(); }
