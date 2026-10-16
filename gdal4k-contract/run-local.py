#!/usr/bin/env python3
"""Build and execute the public gdal4k API contract (not the feasibility probe)."""
import json
import os
from pathlib import Path
import platform
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
results = ROOT / 'gdal4k-contract/build/results'
results.mkdir(parents=True, exist_ok=True)
bridge = ROOT / 'gdal4k/build/bridge'
host = {'Darwin':'MacosArm64', 'Linux':'LinuxX64', 'Windows':'MingwX64'}[platform.system()]
if platform.system() == 'Darwin' and platform.machine() != 'arm64': raise SystemExit('Use an ARM64 macOS host')
if platform.system() == 'Linux' and platform.machine() not in ('x86_64', 'AMD64'): raise SystemExit('Cross-compile linuxArm64 on a supported Kotlin Native host')
gradle = str(ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew'))
env = os.environ.copy()
env['PATH'] = str(bridge) + os.pathsep + env['PATH']
records = []
def run(name, command, marker=False):
    started = time.monotonic()
    completed = subprocess.run(command, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=1200)
    (results / f'{name}.log').write_text(completed.stdout)
    ok = completed.returncode == 0 and (not marker or 'GDAL4K_LIBRARY_CONTRACT_OK' in completed.stdout)
    records.append({'name':name, 'status':'passed' if ok else 'failed', 'seconds':round(time.monotonic()-started, 2)})
    (results / 'report.json').write_text(json.dumps({'platform':platform.platform(), 'checks':records}, indent=2) + '\n')
    print(f'{name}: {records[-1]["status"]}', flush=True)
    if not ok: print(completed.stdout[-10000:]); raise SystemExit(1)
options = ['-DCMAKE_BUILD_TYPE=Release']
if os.name == 'nt': options += ['-DCMAKE_C_COMPILER=gcc', '-DCMAKE_CXX_COMPILER=g++']
run('native-configure', ['cmake','-S','gdal4k/native','-B',str(bridge),'-G','Ninja',*options])
run('native-build', ['cmake','--build',str(bridge),'--parallel','3'])
run('consumer-build', [gradle,'-PportableOnly=true',f'-Pgdal4kBridgePrefix={bridge}', '--console=plain', ':gdal4k-contract:contractJvm',f':gdal4k-contract:linkDebugExecutable{host}',':gdal4k-contract:jsDevelopmentExecutableCompileSync',':gdal4k-contract:wasmJsDevelopmentExecutableCompileSync'], True)
executable = ROOT / f'gdal4k-contract/build/bin/{host[0].lower()+host[1:]}/debugExecutable/gdal4k-contract.{"exe" if os.name == "nt" else "kexe"}'
run('native', [str(executable)], True)
for target in ['js','wasmJs']: run(f'{target}-node', ['node','gdal4k-contract/run-node.mjs',target], True)
run('browser', ['node','gdal4k-contract/run-browser.mjs'], True)
