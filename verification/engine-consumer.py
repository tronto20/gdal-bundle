#!/usr/bin/env python3
"""Verify published common engine selection and opt-in JVM classifiers in a fresh KMP app."""
import argparse
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path
from jvm_binaries import JVM_BINARIES
from publication_versions import ANDROID_ENGINE, ENGINE_COORDINATES, library_version_from_repo

ROOT = Path(__file__).resolve().parent.parent
TARGETS = ['jvm', 'android', 'js', 'wasmJs', 'macosArm64', 'linuxX64', 'linuxArm64', 'mingwX64', 'iosArm64', 'iosSimulatorArm64']
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--repo', type=Path, required=True)
p.add_argument('--targets', default=','.join(TARGETS), help='Comma-separated targets; default checks every supported target')
p.add_argument('--jvm-binary', action='append', choices=JVM_BINARIES, default=[])
p.add_argument('--compile', action='store_true', help='Also compile the selected targets; use a compatible host')
a = p.parse_args()
repo = a.repo.resolve()
targets = a.targets.split(',')
if not targets or not set(targets) <= set(TARGETS):
    p.error('Unknown or empty target list')
version = library_version_from_repo(repo)
configurations = {target: target + ('RuntimeClasspath' if target in ['jvm', 'android'] else 'CompileKlibraries' if target not in ['js', 'wasmJs'] else 'RuntimeClasspath') for target in targets}
expected = {target: [] if target == 'jvm' and not a.jvm_binary else ['gdal4k-engine'] if target == 'jvm' else [ANDROID_ENGINE] if target == 'android' else [ENGINE_COORDINATES[target.lower().replace('wasmjs', 'wasm-js')]] for target in targets}
android = 'id("com.android.kotlin.multiplatform.library") version "8.11.2"' if 'android' in targets else ''
declarations = []
for target in targets:
    declarations.append('androidLibrary { namespace="consumer.engine.selection"; compileSdk=35; minSdk=28 }' if target == 'android' else target + ' { nodejs() }' if target in ['js', 'wasmJs'] else target + '()')
with tempfile.TemporaryDirectory(prefix='gdal4k-engine-consumer-') as temporary:
    project = Path(temporary)
    (project/'settings.gradle.kts').write_text('pluginManagement { repositories { mavenCentral(); google(); gradlePluginPortal() } }\nrootProject.name="common-engine-consumer"\n')
    build = '''import org.gradle.api.artifacts.component.ModuleComponentIdentifier
plugins { kotlin("multiplatform") version "2.4.21"; ''' + android + ''' }
repositories {
    exclusiveContent { forRepository { maven { url=uri(''' + json.dumps(repo.as_uri()) + ''') } }; filter { includeGroup("dev.tronto.gdal4k") } }
    mavenCentral(); google()
}
kotlin {
    jvmToolchain(21)
    ''' + '\n    '.join(declarations) + '''
    sourceSets.commonMain.dependencies {
        implementation("dev.tronto.gdal4k:gdal4k-runtime:''' + version + '''")
        implementation("dev.tronto.gdal4k:gdal4k-engine:''' + version + '''")
    }
}
'''
    if 'jvm' in targets:
        build += 'kotlin.sourceSets.named("jvmMain") { dependencies {\n' + ''.join('runtimeOnly("dev.tronto.gdal4k:gdal4k-engine:' + version + ':' + b + '")\n' for b in a.jvm_binary) + '} }\n'
    build += '''tasks.register("verifyEngineSelection") { doLast {
    val configs = ''' + 'mapOf(' + ','.join(json.dumps(t) + ' to ' + json.dumps(c) for t, c in configurations.items()) + ')' + '''
    val expected = ''' + 'mapOf(' + ','.join(json.dumps(t) + ' to setOf<String>(' + ','.join(json.dumps(m) for m in ms) + ')' for t, ms in expected.items()) + ')' + '''
    val report = mutableMapOf<String, Any>()
    for ((target, name) in configs) {
        val engineArtifacts = configurations.getByName(name).incoming.artifacts.artifacts.filter {
            val id = it.id.componentIdentifier as? ModuleComponentIdentifier
            id?.group == "dev.tronto.gdal4k" && (id.module == "gdal4k-engine" || id.module.startsWith("gdal4k-engine-"))
        }
        val modules = engineArtifacts.map { (it.id.componentIdentifier as ModuleComponentIdentifier).module }.toSet()
        check(modules == expected.getValue(target)) { "$target selected $modules; expected ${expected.getValue(target)}" }
        val files = engineArtifacts.map { it.file.name }.sorted()
        if (target == "jvm") {
            val selected = ''' + 'listOf<String>(' + ','.join(json.dumps(b) for b in a.jvm_binary) + ')' + '''
            check(files.size == selected.size && selected.all { classifier -> files.any { it.endsWith("-$classifier.jar") } }) { "Wrong JVM classifier files: $files" }
        }
        report[target] = mapOf("modules" to modules.sorted(), "files" to files)
        println("ENGINE_SELECTION $target: $files")
    }
    layout.buildDirectory.file("engine-selection.json").get().asFile.apply {
        parentFile.mkdirs(); writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(report)))
    }
} }
'''
    (project/'build.gradle.kts').write_text(build)
    (project/'gradle.properties').write_text('org.gradle.jvmargs=-Xmx2g\nandroid.useAndroidX=true\nkotlin.native.ignoreDisabledTargets=true\n')
    source = project/'src/commonMain/kotlin/EngineConsumer.kt'
    source.parent.mkdir(parents=True)
    source.write_text('import dev.gdal4k.Gdal4k\nimport dev.gdal4k.runtime.GdalRuntime\nsuspend fun acquireRuntime(): GdalRuntime = Gdal4k.runtime()\n')
    tasks = ['verifyEngineSelection']
    if a.compile:
        tasks += [':compileAndroidMain' if t == 'android' else ':compileKotlin' + t[0].upper() + t[1:] for t in targets]
    subprocess.run([str(ROOT/('gradlew.bat' if os.name == 'nt' else 'gradlew')), '-p', str(project), '--console=plain', '--max-workers=2', *tasks], check=True)
    output = ROOT/'verification/results/common-engine-selection'
    output.mkdir(parents=True, exist_ok=True)
    label = 'all' if set(targets) == set(TARGETS) else '-'.join(targets)
    shutil.copy2(project/'build/engine-selection.json', output/f'result-{label}-jvm{len(a.jvm_binary)}.json')
