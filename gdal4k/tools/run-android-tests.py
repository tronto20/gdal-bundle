#!/usr/bin/env python3
"""Run commonTest in the bundled Android AAR and verify a separate Maven consumer."""
import argparse
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--sdk',type=Path,required=True);p.add_argument('--ndk',type=Path,required=True)
p.add_argument('--serial',required=True);p.add_argument('--abi',choices=['arm64-v8a','x86_64'],default='arm64-v8a')
a=p.parse_args()
env=os.environ.copy();env.update(ANDROID_HOME=str(a.sdk.resolve()),ANDROID_SDK_ROOT=str(a.sdk.resolve()),ANDROID_NDK_HOME=str(a.ndk.resolve()),ANDROID_SERIAL=a.serial)
adb=[a.sdk.resolve()/'platform-tools'/('adb.exe' if os.name=='nt' else 'adb'),'-s',a.serial]
def run(*args, capture=False):
 r=subprocess.run([str(x) for x in args],cwd=ROOT,env=env,check=True,text=True,stdout=subprocess.PIPE if capture else None)
 return r.stdout.strip() if capture else None
abi=run(*adb,'shell','getprop','ro.product.cpu.abi',capture=True)
if abi!=a.abi:raise SystemExit(f'Device ABI {abi} differs from {a.abi}')
gradle=ROOT/('gradlew.bat' if os.name=='nt' else 'gradlew')
run(gradle,'--console=plain',f'-Pgdal4kAndroidAbis={a.abi}',':gdal4k:connectedAndroidDeviceTest')
# The publication must contain this ABI; extra ABIs are assembled by the aggregate job.
env['ORG_GRADLE_PROJECT_gdal4kAndroidAbis']=a.abi
run(sys.executable,'verification/consumer.py','--target','android')
reports=list((ROOT/'gdal4k/build/outputs/androidTest-results').rglob('TEST-*.xml'))
if not reports:raise SystemExit('Missing device JUnit reports')
count=0
for report in reports:
 suite=ET.parse(report).getroot()
 if int(suite.get('failures',0)) or int(suite.get('errors',0)):raise SystemExit('Failed '+str(report))
 count+=int(suite.get('tests',0))-int(suite.get('skipped',0))
if count<4:raise SystemExit('Not all bundled common tests executed')
result={'status':'passed','tests':count,'abi':abi,'pageSize':run(*adb,'shell','getconf','PAGE_SIZE',capture=True),'scope':'default bundled commonTest and independent Maven consumer'}
directory=ROOT/'gdal4k/build/results';directory.mkdir(parents=True,exist_ok=True)
(directory/'android.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
