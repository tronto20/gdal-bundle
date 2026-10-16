#!/usr/bin/env python3
"""Fail packaging if a runtime needs producer-installed GDAL or codec libraries."""
import argparse
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path

ROOT=Path(__file__).resolve().parent.parent
p=argparse.ArgumentParser();p.add_argument('platform');a=p.parse_args()
platform=a.platform
base=ROOT/'gdal4k-binary/build'
dependencies=[]
if platform=='web':
    engine=base/'engine/web/gdal4k.mjs'
    # Emscripten SINGLE_FILE stores Wasm in a JS byte string. Capture the exact bytes
    # used by the factory, then validate with GC, atomics, SIMD and EH disabled.
    with tempfile.TemporaryDirectory(prefix='gdal4k-wasm-audit-') as temp:
        wasm=Path(temp)/'engine.wasm'
        script='''import {readFile,writeFile} from 'node:fs/promises';
const source=await readFile(process.argv[1],'utf8');
const needle='function findWasmBinary(){return binaryDecode(';
if(!source.includes(needle)) throw new Error('Unsupported Emscripten single-file layout');
const instrumented=source.replace(needle,'function findWasmBinary(){return globalThis.__gdal4kAuditWasm=binaryDecode(');
const factory=(await import('data:text/javascript;base64,'+Buffer.from(instrumented).toString('base64'))).default;
await factory();
await writeFile(process.argv[2],globalThis.__gdal4kAuditWasm);
'''
        subprocess.run(['node','--input-type=module','-e',script,str(engine),str(wasm)],check=True)
        opt=Path(os.environ['EMSDK'])/'upstream/bin'/('wasm-opt.exe' if os.name=='nt' else 'wasm-opt')
        subprocess.run([str(opt),str(wasm),'--enable-bulk-memory','--enable-sign-ext','--enable-mutable-globals','--enable-nontrapping-float-to-int','--disable-gc','--disable-threads','--disable-simd','--disable-exception-handling','--print-features','-o',str(Path(temp)/'validated.wasm')],check=True)
    dependencies=['ordinary Wasm; GC/threads/SIMD/EH disabled during validation']
elif platform.startswith(('macos','ios')):
    library=base/f'engine/{platform}/cmake/libgdal4k.dylib'
    dependencies=[line.strip().split(' (')[0] for line in subprocess.check_output(['otool','-L',str(library)],text=True).splitlines()[1:]]
    unexpected=[d for d in dependencies if not d.startswith(('/usr/lib/','/System/Library/','@rpath/libgdal4k.dylib'))]
    if unexpected:raise SystemExit('Unbundled Apple dependencies: '+str(unexpected))
else:
    library=base/(f'android-runtime/jniLibs/{platform[8:]}/libgdal4k.so' if platform.startswith('android') else f'jvm-resources/gdal4k/{platform}/'+('gdal4k.dll' if platform.startswith('windows') else 'libgdal4k.so'))
    if platform.startswith('android'):
        ndk=Path(os.environ.get('ANDROID_NDK_HOME',ROOT/'verification/tools/android-ndk-r28c'))
        objdump=next((ndk/'toolchains/llvm/prebuilt').glob('*/bin/llvm-objdump'+('.exe' if os.name=='nt' else '')))
    elif platform.startswith('windows'):
        objdump=Path(os.environ.get('KONAN_DATA_DIR',Path.home()/'.konan'))/'dependencies/msys2-mingw-w64-x86_64-2/bin/objdump.exe'
    else:
        directory=Path(os.environ.get('KONAN_DATA_DIR',Path.home()/'.konan'))/'dependencies'
        objdump=next(directory.glob('llvm-21-*/bin/llvm-objdump'))
    output=subprocess.check_output([str(objdump),'-p',str(library)],text=True)
    if platform.startswith('windows'):
        dependencies=re.findall(r'DLL Name:\s*(\S+)',output)
        allowed={'kernel32.dll','user32.dll','advapi32.dll','ws2_32.dll','crypt32.dll','bcrypt.dll','secur32.dll','ole32.dll','oleaut32.dll','uuid.dll','shell32.dll','msvcrt.dll','ntdll.dll','shlwapi.dll','version.dll','iphlpapi.dll'}
        unexpected=[d for d in dependencies if d.lower() not in allowed and not d.lower().startswith(('api-ms-win-','ext-ms-win-'))]
    else:
        dependencies=re.findall(r'NEEDED\s+(\S+)',output)
        allowed={'libc.so','libm.so','libdl.so','liblog.so','libz.so','libandroid.so'} if platform.startswith('android') else {'libc.so.6','libm.so.6','libdl.so.2','libpthread.so.0','librt.so.1','ld-linux-x86-64.so.2','ld-linux-aarch64.so.1'}
        unexpected=[d for d in dependencies if d not in allowed]
    if not dependencies:raise SystemExit('Could not inspect runtime dependencies')
    if unexpected:raise SystemExit('Unbundled dependencies: '+str(unexpected))
result={'platform':platform,'dependencies':dependencies,'result':'passed'}
destination=ROOT/f'verification/results/engine-audit-{platform}.json';destination.parent.mkdir(parents=True,exist_ok=True)
destination.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
