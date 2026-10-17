// Identical engine workload in Node and Chromium; no Kotlin or input preload.
export async function workload(bridge, source, compactRead, windowOnly) {
    async function call(type, handle, method, parameters = {}) {
        const response = JSON.parse(await bridge.call(JSON.stringify({op: 'runtime', args: {type, handle, method, parameters}})));
        if (response.error) throw new Error(response.error);
        return response.result.value;
    }
    const dataset = await call('GdalRuntime', '0', 'openDataset_String_DatasetOpenOptions', {source, options: {update: false, kind: 2, options: []}});
    try {
        const band = await call('Dataset', dataset, 'getRasterBand_Int', {index: 1});
        let checksum = null, checksumMs = null;
        if (!windowOnly) {
            const checksumStart = performance.now();
            checksum = await call('Band', band, 'checksum'); // All 4096 tiles, 256 MiB of input.
            checksumMs = performance.now() - checksumStart;
            if (checksum <= 0) throw new Error('Invalid full-raster checksum');
        }
        const jxlMs = [];
        let jxlChecksum;
        for (let i = 0; i < (windowOnly ? 0 : 3); i++) {
            const start = performance.now();
            const files = await call('Dataset', dataset, 'translate_String_String_List_String', {driver: 'GTiff', name: 'window.tif', arguments: ['-srcwin', '0', '0', '1024', '1024', '-co', 'COMPRESS=JXL', '-co', 'JXL_LOSSLESS=YES']});
            const output = await call('GdalRuntime', '0', 'openDataset_GdalFiles_DatasetOpenOptions', {files, options: {update: false, kind: 2, options: []}});
            try {
                const outputBand = await call('Dataset', output, 'getRasterBand_Int', {index: 1});
                const actual = await call('Band', outputBand, 'checksum');
                if (jxlChecksum !== undefined && actual !== jxlChecksum) throw new Error('Unstable JXL pixels');
                jxlChecksum = actual;
            } finally { await call('Handle', output, 'close'); }
            jxlMs.push(performance.now() - start);
        }
        const readStart = performance.now();
        const requestBuffer = compactRead ? {bufferSize: 512*512} : {buffer: Array(512*512).fill(0)};
        const requestBytes = JSON.stringify(requestBuffer).length;
        const pixels = await call('Band', band, 'readRaster_Int_Int_Int_Int_ByteArray', {xOffset:0, yOffset:0, xSize:512, ySize:512, ...requestBuffer});
        if (pixels.code !== 0 || pixels.buffer.length !== 512*512 || pixels.buffer[0] !== 42 || pixels.buffer[511] !== 0) throw new Error('Invalid read window');
        return {checksum, checksumMs, jxlChecksum, jxlMs, readMs:performance.now()-readStart, readRequestBytes:requestBytes};
    } finally { await call('Handle', dataset, 'close'); }
}
