#!/usr/bin/env python3
"""Verify real Gradle ordering and optionally execute bootstrap without building GDAL."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
TARGETS = {
    'linuxX64': ('LinuxAmd64', 'linux-amd64'),
    'linuxArm64': ('LinuxArm64', 'linux-arm64'),
    'mingwX64': ('WindowsAmd64', 'windows-amd64'),
}


def gradle(*args, env=None):
    wrapper = ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    command = (['cmd.exe', '/d', '/c'] if os.name == 'nt' else []) + [str(wrapper), '--console=plain', '--max-workers=2', *map(str, args)]
    result = subprocess.run(command, cwd=ROOT, env=env, text=True, encoding='utf-8',
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode:
        print(result.stdout)
        raise RuntimeError('Gradle verification failed')
    return result.stdout


def verify_graph():
    engine_tasks = [':gdal4k-engine:buildEngine' + suffix for suffix, _ in TARGETS.values()]
    output = gradle(*engine_tasks, ':gdal4k-engine:cinteropEngineLinuxX64', '--dry-run')
    tasks = re.findall(r'^(:\S+) SKIPPED$', output, re.MULTILINE)
    for api, (suffix, _) in TARGETS.items():
        prerequisite = ':gdal4k:compileKotlin' + api[0].upper() + api[1:]
        engine = ':gdal4k-engine:buildEngine' + suffix
        if prerequisite not in tasks or engine not in tasks or tasks.index(prerequisite) >= tasks.index(engine):
            raise AssertionError('Missing or incorrectly ordered bootstrap: ' + api + '\n' + output)
    if tasks.index(':gdal4k-engine:buildEngineLinuxAmd64') >= tasks.index(':gdal4k-engine:cinteropEngineLinuxX64'):
        raise AssertionError('Engine cinterop must follow engine production')
    output = gradle(':gdal4k-engine:buildEngineWeb', '--dry-run')
    if re.search(r'^:gdal4k:compileKotlin(?:LinuxX64|LinuxArm64|MingwX64) ', output, re.MULTILINE):
        raise AssertionError('Web must not initialize unrelated Native toolchains')
    print('Gradle graph: three API bootstraps precede engines; cinterop has no cycle; web stays independent')


def groovy_literal(value):
    return "'" + str(value).replace('\\', '\\\\').replace("'", "\\'") + "'"


def probe(target, proof):
    # This is the only replacement for the engine action in the functional test.
    # It uses the actual pinned K/N compiler, never emits a placeholder engine.
    sys.path.insert(0, str(ROOT / 'gdal4k-engine/tools'))
    from toolchains import konan_home, native_toolchain
    home = konan_home()
    config = native_toolchain(TARGETS[target][1], proof / 'toolchain.cmake')
    assert home.is_dir() and config.is_file()
    data = {'target': target, 'compiler_home': str(home),
            'konan_data_dir': os.environ.get('KONAN_DATA_DIR'),
            'target_dependencies_checked': True, 'toolchain_generated': str(config),
            'gdal_engine_built': False}
    (proof / 'result.json').write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(data))


def initialize(target, data_dir, require_empty):
    data_dir = data_dir.resolve()
    fresh = not data_dir.exists() or not any(data_dir.iterdir())
    if require_empty and not fresh:
        raise RuntimeError('Fresh bootstrap check needs an empty dedicated directory; nothing was removed: ' + str(data_dir))
    proof = ROOT / 'build/toolchain-bootstrap' / target
    proof.mkdir(parents=True, exist_ok=True)
    suffix = TARGETS[target][0]
    init = proof / 'probe.gradle'
    arguments = [sys.executable, str(Path(__file__).resolve()), '--probe-target', target, '--proof-directory', str(proof)]
    init.write_text('''// Functional test: keep the production prerequisites, replace only the heavy GDAL action.
gradle.projectsEvaluated {
    def engineProject = rootProject.findProject(':gdal4k-engine')
    if (engineProject == null) return
    engineProject.tasks.named('buildEngine''' + suffix + '''').configure {
        commandLine([''' + ','.join(groovy_literal(a) for a in arguments) + '''])
    }
}
''', encoding='utf-8')
    env = os.environ.copy()
    # Deliberately disagree with the Gradle property to check its precedence.
    env['KONAN_DATA_DIR'] = str(proof / 'unused-env-data')
    env['KONAN_HOME'] = str(proof / 'unused-env-home')
    output = gradle(':gdal4k-engine:buildEngine' + suffix, '-Pkonan.data.dir=' + str(data_dir),
                    '--init-script', init, env=env)
    (proof / 'gradle.log').write_text(output, encoding='utf-8')
    result_path = proof / 'result.json'
    data = json.loads(result_path.read_text(encoding='utf-8'))
    if Path(data['konan_data_dir']).resolve() != data_dir or not Path(data['compiler_home']).resolve().is_relative_to(data_dir):
        raise AssertionError('KGP and Python selected different Native installations: ' + str(data))
    data['initially_empty'] = fresh
    result_path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(data))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--initialize-target', choices=TARGETS)
    parser.add_argument('--data-dir', type=Path)
    parser.add_argument('--require-empty', action='store_true')
    parser.add_argument('--probe-target', choices=TARGETS, help=argparse.SUPPRESS)
    parser.add_argument('--proof-directory', type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.probe_target:
        probe(args.probe_target, args.proof_directory)
        return
    verify_graph()
    if args.initialize_target:
        initialize(args.initialize_target, args.data_dir or ROOT / 'build/toolchain-bootstrap' / args.initialize_target / 'konan', args.require_empty)


if __name__ == '__main__':
    main()
