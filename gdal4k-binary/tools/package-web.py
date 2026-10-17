#!/usr/bin/env python3
"""Package external Worker/ES module/Wasm assets for both Kotlin web targets."""
import json
import shutil
import sys
from pathlib import Path
from gdal_resources import resource_files
from versions import configured_binary_version
ROOT = Path(__file__).resolve().parents[2]
def leb(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128); value >>= 7
    result.append(value)
    return bytes(result)
def package_web(engine):
    generated = ROOT/'gdal4k-binary/build/generated/web'
    if generated.exists(): shutil.rmtree(generated)
    assets = generated/'assets'
    assets.mkdir(parents=True)
    for source in (engine, engine.with_suffix('.wasm')):
        shutil.copy2(source, assets/source.name)
    for source in (ROOT/'gdal4k-binary/web/runtime').glob('*.mjs'):
        shutil.copy2(source, assets/source.name)
    prefix = engine.parent/'installed/gdal4k-web'
    data = bytearray(); entries = []
    for source in resource_files(prefix):
        name = source.relative_to(prefix/'share/gdal').as_posix()
        name.encode('ascii') # The VSI directory bridge uses these fixed ASCII names.
        content = source.read_bytes()
        entries.append({'name': name, 'offset': len(data), 'size': len(content)})
        data.extend(content)
    # KGP extracts .mjs/.wasm assets from Maven KLIBs. Store data in a valid
    # Wasm custom section, read by range without compiling or instantiating it.
    section_name = b'gdal4k.resources'
    section_prefix = leb(len(section_name)) + section_name
    header = b'\0asm\1\0\0\0\0' + leb(len(section_prefix) + len(data)) + section_prefix
    for entry in entries: entry['offset'] += len(header)
    (assets/'gdal4k-data.wasm').write_bytes(header + data)
    (assets/'gdal4k-resources.mjs').write_text('export const resources = '+json.dumps(entries,separators=(',',':'))+';\n'
        'export const resourceBytes = '+str(len(header)+len(data))+';\n'
        "export const resourceUrl = new URL('./gdal4k-data.wasm', import.meta.url);\n")
    (assets/'package.json').write_text(json.dumps({'name':'@gdal4k/web-engine','version':configured_binary_version(),'type':'module','main':'index.mjs'},indent=2)+'\n')
    print(json.dumps({f.name:f.stat().st_size for f in assets.iterdir()}))
if __name__ == '__main__': package_web(Path(sys.argv[1]))
