#!/usr/bin/env python3
"""Stage real JNI libraries and data before :gdal4k:assembleAndroidMain.

Build libgdal4k with CMake/NDK first. Repeated calls add ABIs to the same AAR.
Only this task's build/android-runtime directory is written.
"""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--abi', required=True, choices=['arm64-v8a', 'x86_64', 'armeabi-v7a', 'x86'])
p.add_argument('--bridge', type=Path, required=True)
p.add_argument('--ndk', type=Path, required=True)
p.add_argument('--proj-data', type=Path, required=True)
p.add_argument('--gdal-data', type=Path, required=True)
a = p.parse_args()
for directory in (a.proj_data, a.gdal_data):
    if not directory.is_dir(): p.error(f'Missing data directory: {directory}')
if not (a.proj_data / 'proj.db').is_file(): p.error('Missing proj.db')
triple = {'arm64-v8a':'aarch64-linux-android', 'x86_64':'x86_64-linux-android', 'armeabi-v7a':'arm-linux-androideabi', 'x86':'i686-linux-android'}[a.abi]
prebuilt = next((a.ndk / 'toolchains/llvm/prebuilt').iterdir())
output = root / 'build/android-runtime'
lib = output / 'jniLibs' / a.abi
lib.mkdir(parents=True, exist_ok=True)
shutil.copy2(a.bridge, lib / 'libgdal4k.so')
shutil.copy2(prebuilt / f'sysroot/usr/lib/{triple}/libc++_shared.so', lib / 'libc++_shared.so')
subprocess.run([str(prebuilt / 'bin/llvm-strip'), '--strip-unneeded', str(lib / 'libgdal4k.so')], check=True)
assets = output / 'assets/gdal4k'
for name, directory in [('proj', a.proj_data), ('gdal', a.gdal_data)]:
    dest = assets / name
    if dest.exists(): shutil.rmtree(dest)
    shutil.copytree(directory, dest)
manifest = []
for file in sorted(assets.rglob('*')):
    if file.is_file() and file.name != 'manifest.txt':
        manifest.append(f'{hashlib.sha256(file.read_bytes()).hexdigest()}  {file.relative_to(assets).as_posix()}')
(assets / 'manifest.txt').write_text('\n'.join(manifest) + '\n')
print(f'Staged Android {a.abi}: {lib} and {len(manifest)} data assets')
