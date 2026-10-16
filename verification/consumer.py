#!/usr/bin/env python3
"""Verify published Maven artifacts from a fresh, unrelated Kotlin consumer build."""
import argparse
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT=Path(__file__).resolve().parent.parent
VERSION='3.12.4-1.0.1-SNAPSHOT'
p=argparse.ArgumentParser()
p.add_argument('--target', required=True, choices=['jvm','js','wasmJs','macosArm64','macosX64','linuxX64','linuxArm64','mingwX64','iosArm64','iosSimulatorArm64','iosX64','android'])
p.add_argument('--repo',type=Path,default=ROOT/'verification/build/consumer-maven')
p.add_argument('--no-publish',action='store_true')
p.add_argument('--compile-only',action='store_true')
p.add_argument('--browser',action='store_true')
p.add_argument('--ios-device')
a=p.parse_args()
repo=a.repo.resolve(); target=a.target; title=target[0].upper()+target[1:]
gradle=ROOT/('gradlew.bat' if os.name=='nt' else 'gradlew')
def run(args,**kwargs):subprocess.run([str(x) for x in args],check=True,**kwargs)
if not a.no_publish:
 tasks=[]
 for module in ['gdal4k-runtime','gdal4k-binary','gdal4k']:
  tasks += [f':{module}:publishKotlinMultiplatformPublicationToMavenLocal',f':{module}:publish{title}PublicationToMavenLocal']
 if target=='android':tasks+=[':gdal4k-android-runtime:publishAndroidRuntimePublicationToMavenLocal']
 run([gradle,'--console=plain',f'-Dmaven.repo.local={repo}',*tasks],cwd=ROOT)
source='''package consumer

import dev.gdal4k.Gdal4k
import dev.gdal4k.runtime.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class PublishedConsumerTest {
    @Test fun defaultRuntimeAndJxl() = runTest(timeout = 120.seconds) {
        Gdal4k.prepare()
        try {
            val runtime = Gdal4k.runtime()
            assertEquals("3120400", runtime.versionInfo("VERSION_NUM"))
            assertTrue(runtime.versionInfo("THIRD_PARTY_LICENSES").contains("JPEG"))
            val expected = byteArrayOf(0, 1, 127, -1)
            requireNotNull(runtime.getDriver("MEM")).use { driver ->
                requireNotNull(driver.create("", 2, 2, 1, GdalDataType.Byte)).use { source ->
                    assertEquals(0, requireNotNull(source.getRasterBand(1)).writeRaster(expected))
                    runtime.createSpatialReference("EPSG:4326").use { crs ->
                        assertEquals(0, source.setProjection(crs.exportToWkt()))
                    }
                    assertEquals(0, source.setGeoTransform(doubleArrayOf(100.0,0.5,0.0,50.0,0.0,-0.5)))
                    for (format in listOf("JPEGXL", "GTiff", "COG")) {
                        val path = "/vsimem/consumer-" + format + if(format=="JPEGXL") ".jxl" else ".tif"
                        val options = if(format=="JPEGXL") listOf("LOSSLESS=YES") else listOf("COMPRESS=JXL", "JXL_LOSSLESS=YES")
                        requireNotNull(runtime.getDriver(format)).use { outputDriver ->
                            requireNotNull(outputDriver.createCopy(path, source, options=options)).close()
                        }
                        runtime.openDataset(path).use { reopened ->
                            val actual=ByteArray(4)
                            assertEquals(0,requireNotNull(reopened.getRasterBand(1)).readRaster(actual))
                            assertContentEquals(expected, actual)
                            if(format!="JPEGXL") assertTrue(runtime.datasetInfo(reopened,DatasetInfoOptions(listOf("-json"))).contains("JXL"))
                        }
                        runtime.deleteMemoryFile(path)
                    }
                }
            }
        } finally { Gdal4k.close() }
        println("GDAL4K_PUBLISHED_CONSUMER_OK")
    }
}
'''
with tempfile.TemporaryDirectory(prefix='gdal4k-published-consumer-') as temporary:
 consumer=Path(temporary)
 (consumer/'settings.gradle.kts').write_text('pluginManagement { repositories { google(); gradlePluginPortal(); mavenCentral() } }\nrootProject.name="published-gdal4k-consumer"\n')
 android='id("com.android.kotlin.multiplatform.library") version "8.11.2"' if target=='android' else ''
 if target=='jvm':declaration='jvm()';task='jvmTest'
 elif target in ['js','wasmJs']:
  declaration=target+' { nodejs { testTask { useMocha { timeout="180s" } } }; browser { testTask { useKarma { useChromeHeadless() } } } }'
  task=target+('BrowserTest' if a.browser else 'NodeTest')
 elif target=='android':
  declaration='''androidLibrary { namespace="consumer.gdal4k"; compileSdk=35; minSdk=28
withDeviceTestBuilder { sourceSetTreeName="test" }.configure { instrumentationRunner="androidx.test.runner.AndroidJUnitRunner" } }'''
  task='connectedAndroidDeviceTest'
 else:
  declaration=target+'()'
  if target in ['iosSimulatorArm64','iosX64'] and a.ios_device:
   declaration=target+' { testRuns["test"].deviceId = '+json.dumps(a.ios_device)+' }'
  task='linkDebugTest'+title if a.compile_only else target+'Test'
 extra='getByName("androidDeviceTest").dependencies { implementation("androidx.test:runner:1.6.2") }' if target=='android' else ''
 build='''plugins { kotlin("multiplatform") version "2.4.21"; '''+android+''' }
repositories {
    exclusiveContent { forRepository { maven { url=uri('''+json.dumps(repo.as_uri())+''') } }; filter { includeGroup("dev.tronto.gdal4k") } }
    google(); mavenCentral()
}
kotlin {
    jvmToolchain(21)
    '''+declaration+'''
    sourceSets {
        commonMain.dependencies { implementation("dev.tronto.gdal4k:gdal4k:'''+VERSION+'''") }
        commonTest.dependencies { implementation(kotlin("test")); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2") }
        '''+extra+'''
    }
}
'''
 (consumer/'build.gradle.kts').write_text(build)
 (consumer/'gradle.properties').write_text('org.gradle.jvmargs=-Xmx2g\nandroid.useAndroidX=true\n')
 path=consumer/'src/commonTest/kotlin/consumer/PublishedConsumerTest.kt';path.parent.mkdir(parents=True);path.write_text(source)
 config=consumer/'karma.config.d/timeout.js';config.parent.mkdir();config.write_text('config.browserNoActivityTimeout=240000; config.client.mocha={...config.client.mocha,timeout:180000};\n')
 if target=='android' and (ROOT/'local.properties').exists():shutil.copy2(ROOT/'local.properties',consumer/'local.properties')
 # The native consumer must link the archive stored inside its published KLIB.
 platform={'macosArm64':'macos-arm64','macosX64':'macos-amd64','linuxX64':'linux-amd64','linuxArm64':'linux-arm64','mingwX64':'windows-amd64','iosArm64':'ios-arm64','iosSimulatorArm64':'ios-simulator-arm64','iosX64':'ios-simulator-amd64'}.get(target)
 hidden=[]
 try:
  if platform:
   for original in [ROOT/f'gdal4k-binary/build/engine/{platform}',ROOT/f'gdal4k-binary/build/classes/kotlin/{target}/main/cinterop']:
    if original.exists():
     moved=original.with_name(original.name+'-consumer-hidden')
     if moved.exists():raise RuntimeError('Previous hidden producer files must be restored: '+str(moved))
     original.rename(moved);hidden.append((original,moved))
  run([gradle,'-p',consumer,'--console=plain',task],cwd=consumer)
  destination=ROOT/'verification/results'/('published-consumer-'+target+('-browser' if a.browser else ''))
  destination.mkdir(parents=True,exist_ok=True)
  for folder in ['build/test-results','build/outputs/androidTest-results','build/bin']:
   if (consumer/folder).exists():shutil.copytree(consumer/folder,destination/Path(folder).name,dirs_exist_ok=True)
  (destination/'result.json').write_text(json.dumps({'target':target,'version':VERSION,'browser':a.browser,'compiled_only':a.compile_only,'producer_paths_hidden':bool(hidden),'result':'passed'},indent=2)+'\n')
 finally:
  for original,moved in reversed(hidden):moved.rename(original)
