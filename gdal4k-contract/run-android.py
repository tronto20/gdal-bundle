#!/usr/bin/env python3
"""Build the production Android AAR/APK and execute the shared API contract on an explicit device."""
import argparse
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--sdk', type=Path, required=True)
p.add_argument('--ndk', type=Path, required=True)
p.add_argument('--vcpkg', type=Path, required=True)
p.add_argument('--gdal-dir', type=Path)
p.add_argument('--serial', required=True)
p.add_argument('--abi', choices=['arm64-v8a','x86_64'], default='arm64-v8a')
p.add_argument('--cmake', default='cmake')
a = p.parse_args()
for name in ('sdk','ndk','vcpkg','gdal_dir'):
    if getattr(a, name): setattr(a, name, getattr(a, name).resolve())
os.chdir(ROOT)
results = ROOT / 'gdal4k-contract/build/results'
results.mkdir(parents=True, exist_ok=True)
log = results / 'android-run.log'
log.write_text('')
env = os.environ.copy()
env['ANDROID_HOME'] = str(a.sdk)
adb = [str(a.sdk / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')), '-s', a.serial]
def run(command, marker=None):
    result = subprocess.run([str(x) for x in command], env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=1200)
    with log.open('a') as stream: stream.write(result.stdout)
    if result.returncode or (marker and marker not in result.stdout):
        print(result.stdout[-8000:]); raise SystemExit('Android contract failed: ' + str(command[0]))
    return result.stdout.strip()
abi = run([*adb,'shell','getprop','ro.product.cpu.abi'])
if abi != a.abi: raise SystemExit(f'Device ABI {abi!r} differs from requested {a.abi}')
triplet = 'arm64-android' if a.abi == 'arm64-v8a' else 'x64-android'
prefix = a.vcpkg / 'installed' / triplet
build = ROOT / 'gdal4k/build' / f'android-{a.abi}'
options = [f'-DCMAKE_TOOLCHAIN_FILE={a.vcpkg}/scripts/buildsystems/vcpkg.cmake', f'-DVCPKG_TARGET_TRIPLET={triplet}', f'-DVCPKG_CHAINLOAD_TOOLCHAIN_FILE={a.ndk}/build/cmake/android.toolchain.cmake', f'-DANDROID_ABI={a.abi}', '-DANDROID_PLATFORM=android-28', '-DCMAKE_BUILD_TYPE=Release']
if a.gdal_dir: options += [f'-DGDAL_DIR={a.gdal_dir}']
run([a.cmake,'-S','gdal4k/native','-B',build,'-G','Ninja',*options])
run([a.cmake,'--build',build,'--parallel','3'])
gdal_data = a.gdal_dir.parents[2] / 'share/gdal' if a.gdal_dir else prefix / 'share/gdal'
import sys
run([sys.executable,'gdal4k/tools/package-android.py','--abi',a.abi,'--bridge',build/'libgdal4k.so','--ndk',a.ndk,'--proj-data',prefix/'share/proj','--gdal-data',gdal_data])
run([ROOT/('gradlew.bat' if os.name=='nt' else 'gradlew'),'-PportableOnly=true','--console=plain',':gdal4k:assembleAndroidMain',':gdal4k-android-runtime:assembleRelease',':gdal4k-android-test:assembleDebug'])
run([*adb,'install','-r',ROOT/'gdal4k-android-test/build/outputs/apk/debug/gdal4k-android-test-debug.apk'])
# The test application belongs to this repository; clearing it forces cold data extraction.
run([*adb,'shell','pm','clear','dev.gdal4k.contract.app'], 'Success')
output = run([*adb,'shell','am','instrument','-w','dev.gdal4k.contract.app/.ContractInstrumentation'], 'result=GDAL4K_LIBRARY_CONTRACT_OK')
if 'INSTRUMENTATION_CODE: -1' not in output: raise SystemExit(output)
report = {'status':'passed','abi':abi,'androidVersion':run([*adb,'shell','getprop','ro.build.version.release']), 'pageSize':run([*adb,'shell','getconf','PAGE_SIZE']), 'head':run(['git','rev-parse','HEAD']), 'scope':'Production AAR dependencies, native/data packaging, cold application data extraction, shared Kotlin API contract', 'output':output}
(results/'android.json').write_text(json.dumps(report,indent=2)+'\n')
print(output)
