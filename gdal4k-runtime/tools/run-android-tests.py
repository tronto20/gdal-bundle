#!/usr/bin/env python3
"""Run commonTest in the bundled Android AAR and verify a separate Maven consumer."""
import argparse
import json
import os
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--sdk',type=Path,required=True);p.add_argument('--ndk',type=Path,required=True)
p.add_argument('--serial',required=True);p.add_argument('--abi',choices=['arm64-v8a'],default='arm64-v8a')
p.add_argument('--allow-native-bridge',action='store_true',help='Allow an x86_64 CI emulator that explicitly advertises ARM64 translation')
a=p.parse_args()
env=os.environ.copy();env.update(ANDROID_HOME=str(a.sdk.resolve()),ANDROID_SDK_ROOT=str(a.sdk.resolve()),ANDROID_NDK_HOME=str(a.ndk.resolve()),ANDROID_SERIAL=a.serial)
adb=[a.sdk.resolve()/'platform-tools'/('adb.exe' if os.name=='nt' else 'adb'),'-s',a.serial]
def run(*args, capture=False):
 r=subprocess.run([str(x) for x in args],cwd=ROOT,env=env,check=True,text=True,stdout=subprocess.PIPE if capture else None)
 return r.stdout.strip() if capture else None
device_abi=run(*adb,'shell','getprop','ro.product.cpu.abi',capture=True)
supported=run(*adb,'shell','getprop','ro.product.cpu.abilist',capture=True).split(',')
native_bridge=run(*adb,'shell','getprop','ro.dalvik.vm.native.bridge',capture=True)
translated=device_abi!=a.abi
if translated and not (a.allow_native_bridge and device_abi=='x86_64' and a.abi in supported and native_bridge not in ['', '0']):
 raise SystemExit(f'Device ABI {device_abi} cannot execute {a.abi}; supported={supported}; native bridge={native_bridge}')
gradle=ROOT/('gradlew.bat' if os.name=='nt' else 'gradlew')
run(gradle,'--console=plain',f'-Pgdal4kAndroidAbis={a.abi}',':gdal4k-runtime:connectedAndroidDeviceTest')
payloads=[]
for apk in (ROOT/'gdal4k-runtime/build/outputs/apk').rglob('*.apk'):
 with zipfile.ZipFile(apk) as archive:
  libraries={name for name in archive.namelist() if name.startswith('lib/') and name.endswith('/libgdal4k.so')}
  if libraries:
   if libraries!={'lib/arm64-v8a/libgdal4k.so'}:raise SystemExit(f'Unsupported GDAL ABI in {apk}: {libraries}')
   payloads.append(str(apk.relative_to(ROOT)))
if not payloads:raise SystemExit('No ARM64 GDAL payload found in the test APKs')
# Both the test APK and independent consumer use only the supported ARM64 payload.
env['ORG_GRADLE_PROJECT_gdal4kAndroidAbis']=a.abi
run(sys.executable,'verification/consumer.py','--target','android')
reports=list((ROOT/'gdal4k-runtime/build/outputs/androidTest-results').rglob('TEST-*.xml'))
if not reports:raise SystemExit('Missing device JUnit reports')
count=0
for report in reports:
 suite=ET.parse(report).getroot()
 if int(suite.get('failures',0)) or int(suite.get('errors',0)):raise SystemExit('Failed '+str(report))
 count+=int(suite.get('tests',0))-int(suite.get('skipped',0))
if count<5:raise SystemExit('Not all bundled common tests executed')
result={'status':'passed','tests':count,'abi':a.abi,'deviceAbi':device_abi,'translated':translated,'nativeBridge':native_bridge,'apks':payloads,'pageSize':run(*adb,'shell','getconf','PAGE_SIZE',capture=True),'scope':'default bundled commonTest and independent Maven consumer'}
directory=ROOT/'gdal4k-runtime/build/results';directory.mkdir(parents=True,exist_ok=True)
(directory/'android.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
