#!/usr/bin/env python3
"""Small real compiler checks for prepared tools, independent of GDAL/SDK test runtimes."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys


def run(*command, **kwargs):
    return subprocess.run([str(x) for x in command], check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--environment-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--native-only', action='store_true')
    args = parser.parse_args()
    native = json.loads((args.environment_dir / 'native.json').read_text(encoding='utf-8'))
    output = args.output.resolve()
    source = output / 'source'
    source.mkdir(parents=True, exist_ok=True)
    (source / 'CMakeLists.txt').write_text('cmake_minimum_required(VERSION 3.20)\nproject(tool_smoke LANGUAGES C CXX)\nadd_executable(tool_smoke main.cpp)\n', encoding='utf-8')
    (source / 'main.cpp').write_text('#include <cstdio>\nint main() { std::puts("build-tools-ok"); return 0; }\n', encoding='utf-8')
    env = os.environ.copy()
    env.update({k: v for k, v in native.items() if k != 'PATH_PREFIX'})
    env['PATH'] = native['PATH_PREFIX'] + os.pathsep + env.get('PATH', '')
    cmake, ninja = native['CMAKE_COMMAND'], native['NINJA_COMMAND']
    run(cmake, '--version', env=env)
    run(ninja, '--version', env=env)
    vcpkg = Path(native['VCPKG_ROOT']) / ('vcpkg.exe' if os.name == 'nt' else 'vcpkg')
    run(vcpkg, 'version', env=env)
    build = output / 'native'
    run(cmake, '-S', source, '-B', build, '-G', 'Ninja', '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_MAKE_PROGRAM=' + ninja, env=env)
    run(cmake, '--build', build, '--parallel', '2', env=env)
    result = run(build / ('tool_smoke.exe' if os.name == 'nt' else 'tool_smoke'), env=env, capture_output=True, text=True)
    assert result.stdout.strip() == 'build-tools-ok', result.stdout
    evidence = {'native': 'compiled and executed', 'web': 'not requested'}
    if not args.native_only:
        web = json.loads((args.environment_dir / 'web.json').read_text(encoding='utf-8'))
        env.update({k: v for k, v in web.items() if k != 'PATH_PREFIX'})
        env['PATH'] = web['PATH_PREFIX'] + os.pathsep + os.environ.get('PATH', '')
        sdk = Path(web['EMSDK'])
        emcc = sdk / 'upstream/emscripten/emcc.py'
        run(sys.executable, emcc, '--version', env=env)
        build = output / 'web'
        run(cmake, '-S', source, '-B', build, '-G', 'Ninja', '-DCMAKE_BUILD_TYPE=Release',
            '-DCMAKE_MAKE_PROGRAM=' + ninja,
            '-DCMAKE_TOOLCHAIN_FILE=' + str(sdk / 'upstream/emscripten/cmake/Modules/Platform/Emscripten.cmake'), env=env)
        run(cmake, '--build', build, '--parallel', '2', env=env)
        # Ask the installed SDK which Node it configured rather than assuming a version/layout.
        os.environ['EM_CONFIG'] = web['EM_CONFIG']
        config = {'__file__': web['EM_CONFIG']}
        exec(Path(web['EM_CONFIG']).read_text(encoding='utf-8'), config)
        node = config['NODE_JS']
        node = [node] if isinstance(node, str) else node
        result = run(*node, build / 'tool_smoke.js', env=env, capture_output=True, text=True)
        assert result.stdout.strip() == 'build-tools-ok', result.stdout
        evidence['web'] = 'compiled to Wasm and executed with SDK Node'
    output.mkdir(parents=True, exist_ok=True)
    (output / 'result.json').write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(evidence))


if __name__ == '__main__':
    main()
