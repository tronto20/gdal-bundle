#!/usr/bin/env python3
"""Package external Worker/ES module/Wasm assets for both Kotlin web targets."""
import json
import shutil
import sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
def package_web(engine):
    generated = ROOT/'gdal4k-binary/build/generated/web'
    if generated.exists(): shutil.rmtree(generated)
    assets = generated/'assets'
    assets.mkdir(parents=True)
    for source in (engine, engine.with_suffix('.wasm')):
        shutil.copy2(source, assets/source.name)
    for source in (ROOT/'gdal4k-binary/web/runtime').glob('*.mjs'):
        shutil.copy2(source, assets/source.name)
    (assets/'package.json').write_text(json.dumps({'name':'@gdal4k/web-engine','version':'3.12.4-1.0.1-SNAPSHOT','type':'module','main':'index.mjs'},indent=2)+'\n')
    print(json.dumps({f.name:f.stat().st_size for f in assets.iterdir()}))
if __name__ == '__main__': package_web(Path(sys.argv[1]))
