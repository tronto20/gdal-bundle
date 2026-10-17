#!/usr/bin/env python3
"""Embed one ordinary GDAL Wasm ES module in both Kotlin web artifacts."""
import base64
import json
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def package_web(engine):
    original = engine.read_bytes()
    compressor = zlib.compressobj(level=9, wbits=-15)
    compressed = compressor.compress(original) + compressor.flush()
    data = base64.b64encode(compressed).decode()
    generated = ROOT/'gdal4k-binary/build/generated/web/kotlin/dev/gdal4k'
    generated.mkdir(parents=True, exist_ok=True)
    chunks = [data[i:i+16000] for i in range(0,len(data),16000)]
    prefix = f'data:application/x-gdal4k-deflate;size={len(original)};base64,'
    (generated/'BundledWebEngine.kt').write_text('package dev.gdal4k\n\ninternal actual fun bundledEngineUrl(): String = '+json.dumps(prefix)+' + listOf(\n'+',\n'.join('    "'+c+'"' for c in chunks)+'\n).joinToString("")\n')
    vendor = ROOT/'gdal4k-binary/web/vendor/tiny-inflate'
    source = '/*\n'+(vendor/'LICENSE').read_text()+'\n*/\n'+(vendor/'index.js').read_text()
    # The vendored ES5 decoder adds no browser feature requirement and runs only in the Worker.
    (generated/'BundledWebInflate.kt').write_text('package dev.gdal4k\n\ninternal actual val gdalInflateSource: String = '+json.dumps(source)+'\n')
    print(json.dumps({'module_bytes':len(original),'embedded_bytes':len(data)}))

if __name__ == '__main__':
    package_web(Path(sys.argv[1]))
