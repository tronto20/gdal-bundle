export async function createGdal4k(assets) {
  let worker;
  const pending = new Map();
  let sequence = 0;
  let stopped = false;
  const receive = message => {
    const entry = pending.get(message.id);
    if (!entry) return;
    pending.delete(message.id);
    if (message.error) entry.reject(new Error(message.error)); else entry.resolve(message.result);
  };
  const failed = error => {
    stopped = true;
    for (const entry of pending.values()) entry.reject(error instanceof Error ? error : new Error(String(error)));
    pending.clear();
  };
  if (typeof process !== 'undefined' && process.versions?.node) {
    const { Worker } = await import('node:worker_threads');
    worker = new Worker(new URL('./gdal4k-worker.mjs', import.meta.url));
    worker.on('message', receive); worker.on('error', failed);
    worker.on('exit', code => { if (!stopped) failed(new Error(`GDAL worker exited (${code})`)); });
  } else {
    worker = new Worker(new URL('./gdal4k-worker.mjs', import.meta.url), { type: 'module' });
    worker.onmessage = event => receive(event.data);
    worker.onerror = error => failed(new Error(error.message));
  }
  const send = (op, request) => new Promise((resolve, reject) => {
    if (stopped) { reject(new Error('GDAL worker is closed')); return; }
    const id = ++sequence;
    pending.set(id, { resolve, reject });
    worker.postMessage({ id, op, request, assets });
  });
  try { await send('initialize'); }
  catch (error) { stopped = true; await worker.terminate(); throw error; }
  return {
    call: request => send('call', request),
    async close() {
      if (stopped) return;
      try { await send('close'); }
      finally { stopped = true; await worker.terminate(); failed(new Error('GDAL worker is closed')); }
    },
  };
}
