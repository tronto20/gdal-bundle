// Read-only random access. File contents never become a MEMFS or /vsimem file.
const CHUNK = 64 * 1024;
const CACHE_LIMIT = 8 * 1024 * 1024;
const nodeRuntime = typeof process !== 'undefined' && process.versions?.node;
const nodeFs = nodeRuntime ? process.getBuiltinModule('fs') : null;
function range(response, start, end, total) {
    const match = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(response);
    if (!match || +match[1] !== start || +match[2] !== end || (total !== undefined && +match[3] !== total)) {
        throw new Error('Invalid HTTP Content-Range; expose this header through CORS');
    }
    const size = +match[3];
    if (!Number.isSafeInteger(size) || size <= end) throw new Error('Invalid HTTP file size');
    return size;
}
async function readExact(response, expected) {
    const reader = response.body.getReader();
    const bytes = new Uint8Array(expected);
    let count = 0;
    try {
        for (;;) {
            const {done, value} = await reader.read();
            if (done) break;
            if (count + value.length > expected) throw new Error('Invalid HTTP range payload length');
            bytes.set(value, count); count += value.length;
        }
        if (count !== expected) throw new Error('Invalid HTTP range payload length');
        return bytes;
    } finally { await reader.cancel(); }
}
async function httpSource(url) {
    async function fetchRange(start, end, size, validator) {
        const headers = {Range: `bytes=${start}-${end}`};
        if (validator) headers['If-Range'] = validator;
        const response = await fetch(url, {headers, credentials: 'same-origin', signal: AbortSignal.timeout(30000)});
        try {
            if (response.status !== 206) throw new Error('HTTP input requires 206 byte-range responses; full-download fallback is disabled');
            const total = range(response.headers.get('Content-Range'), start, end, size);
            if (validator && response.headers.get('ETag') !== validator) throw new Error('HTTP input changed during reading');
            const bytes = await readExact(response, end - start + 1);
            return {total, bytes, etag: response.headers.get('ETag')};
        } catch (error) { await response.body?.cancel().catch(() => {}); throw error; }
    }
    const initial = await fetchRange(0, 0);
    const validator = initial.etag && !initial.etag.startsWith('W/') ? initial.etag : null;
    return {
        size: initial.total,
        async read(start, end) { return (await fetchRange(start, end, initial.total, validator)).bytes; },
        close() {},
    };
}

export class Inputs {
    constructor() {
        this.sequence = 0;
        this.providers = new Map();
        this.cache = new Map();
        this.cachedBytes = 0;
        this.lastError = null;
        this.closeResources = null;
    }
    key(path) { return path.startsWith('/vsigdal4k/') ? path : '/vsigdal4k/' + path; }
    size(path) {
        path = this.key(path);
        const file = this.providers.get(path);
        if (file) return file.size;
        const prefix = path.endsWith('/') ? path : path + '/';
        return [...this.providers.keys()].some(key => key.startsWith(prefix)) ? -2 : -1;
    }
    list(path) {
        path = this.key(path); const prefix = path.endsWith('/') ? path : path + '/';
        return [...new Set([...this.providers.keys()].filter(key => key.startsWith(prefix)).map(key => key.slice(prefix.length).split('/')[0]))].sort();
    }
    registerResources(entries, url, size) {
        let source, opening;
        const open = async () => {
            if (!opening) opening = (async () => {
                if (nodeRuntime) {
                    const fd = nodeFs.openSync(url, 'r');
                    let stat;
                    try { stat = nodeFs.fstatSync(fd); } catch (error) { nodeFs.closeSync(fd); throw error; }
                    source = {size: stat.size, read(start, end) {
                        const bytes = new Uint8Array(end-start+1);
                        return bytes.subarray(0, nodeFs.readSync(fd, bytes, 0, bytes.length, start));
                    }, close() { nodeFs.closeSync(fd); }};
                } else source = await httpSource(url);
                if (source.size !== size) throw new Error('Bundled GDAL resource asset size mismatch');
                return source;
            })().catch(error => { source?.close(); source = null; opening = null; throw error; });
            return opening;
        };
        for (const entry of entries) this.providers.set('/vsigdal4k/resources/' + entry.name, {
            size: entry.size,
            async read(start, end) { return (await open()).read(entry.offset + start, entry.offset + end); },
            close() {},
        });
        this.closeResources = () => { source?.close(); source = null; opening = null; };
    }
    close() {
        for (const provider of this.providers.values()) provider.close();
        this.providers.clear(); this.cache.clear(); this.cachedBytes = 0;
        this.closeResources?.(); this.closeResources = null;
    }
    async read(path, position, length) {
        path = this.key(path);
        const provider = this.providers.get(path);
        if (!provider) throw new Error('Input has been closed');
        if (!Number.isSafeInteger(position) || position < 0) throw new Error('Invalid input offset');
        const count = Math.min(length, Math.max(0, provider.size - position));
        const output = new Uint8Array(count);
        let copied = 0;
        while (copied < count) {
            const start = Math.floor((position + copied) / CHUNK) * CHUNK;
            const end = Math.min(start + CHUNK, provider.size) - 1;
            const key = path + ':' + start;
            let chunk = this.cache.get(key);
            if (chunk) this.cache.delete(key);
            else {
                chunk = await provider.read(start, end);
                if (chunk.length !== end - start + 1) throw new Error('Input changed or returned a short read');
                this.cachedBytes += chunk.length;
            }
            this.cache.set(key, chunk);
            while (this.cachedBytes > CACHE_LIMIT) {
                const oldest = this.cache.keys().next().value;
                this.cachedBytes -= this.cache.get(oldest).length;
                this.cache.delete(oldest);
            }
            const from = position + copied - start;
            const size = Math.min(count - copied, chunk.length - from);
            output.set(chunk.subarray(from, from + size), copied);
            copied += size;
        }
        return output;
    }
    async mount(source, file) {
        let provider, name;
        if (file) {
            if (typeof file.slice !== 'function') throw new Error('Expected a selected browser File/Blob');
            provider = {size: file.size, read: async (start, end) => new Uint8Array(await file.slice(start, end + 1).arrayBuffer()), close() {}};
            name = source;
        } else if (/^https?:\/\//i.test(source)) {
            provider = await httpSource(source);
            name = decodeURIComponent(new URL(source).pathname.split('/').pop()) || 'input';
        } else {
            const fd = nodeFs.openSync(source, 'r');
            try {
                const stat = nodeFs.fstatSync(fd);
                if (!stat.isFile()) throw new Error('Input path is not a regular file');
                provider = {size: stat.size, read(start, end) {
                    const buffer = new Uint8Array(end - start + 1);
                    const count = nodeFs.readSync(fd, buffer, 0, buffer.length, start);
                    return buffer.subarray(0, count);
                }, close() { nodeFs.closeSync(fd); }};
            } catch (error) { nodeFs.closeSync(fd); throw error; }
            name = process.getBuiltinModule('path').basename(source);
        }
        if (!Number.isSafeInteger(provider.size) || provider.size < 0) { provider.close(); throw new Error('Unsupported input file size'); }
        name = name.replace(/[\/\\\0]/g, '_');
        if (!name || name === '.' || name === '..') name = 'input';
        const path = `/vsigdal4k/${++this.sequence}/${name}`;
        this.providers.set(path, provider);
        let closed = false;
        return {path, close: () => {
            if (closed) return;
            closed = true;
            this.providers.delete(path);
            provider.close();
            for (const [key, value] of this.cache) if (key.startsWith(path + ':')) {
                this.cache.delete(key); this.cachedBytes -= value.length;
            }
        }};
    }
}
