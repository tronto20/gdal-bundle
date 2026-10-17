#!/usr/bin/env python3
"""Run the default bundled host and web library tests; no system GDAL is used."""
import os
import platform
import subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
host={('Darwin','arm64'):'macosArm64',('Darwin','x86_64'):'macosX64',('Linux','x86_64'):'linuxX64',('Windows','AMD64'):'mingwX64'}.get((platform.system(),platform.machine()))
if not host:raise SystemExit('Use the cross-build CI runtime job for this host')
def run(*args):subprocess.run([str(x) for x in args],cwd=ROOT,check=True)
gradle=ROOT/('gradlew.bat' if os.name=='nt' else 'gradlew')
run(gradle,f':gdal4k-runtime:compileKotlin{host[0].upper()+host[1:]}')
run(gradle,'--console=plain',':gdal4k:jvmTest',f':gdal4k:{host}Test',':gdal4k:jsNodeTest',':gdal4k:wasmJsNodeTest',':gdal4k:jsBrowserTest',':gdal4k:wasmJsBrowserTest')
