#!/usr/bin/env python3
"""Cross-host CI driver: Windows uses gradlew.bat without Git Bash."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def gradle(*args, **kwargs):
    command = (['cmd.exe', '/d', '/c', str(ROOT / 'gradlew.bat')] if os.name == 'nt' else [str(ROOT / 'gradlew')])
    return subprocess.run(command + list(args) + ['--max-workers=2', '--console=plain'], cwd=ROOT, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('test', 'prepare', 'compile', 'unsupported-sdk'))
    args = parser.parse_args()
    web = os.environ.get('WEB', 'true').lower() == 'true'
    if args.phase == 'test':
        gradle('-p', 'build-logic', 'check', check=True)
    elif args.phase == 'prepare':
        task = 'webEnvironment' if web else 'nativeEnvironment'
        gradle('-p', 'build-logic/smoke', task, '--configuration-cache', check=True)
        gradle('-p', 'build-logic/smoke', task, '--offline', '--configuration-cache', check=True)
    elif args.phase == 'compile':
        subprocess.run([sys.executable, str(ROOT / 'build-logic/smoke.py'), '--environment-dir',
                        str(ROOT / 'build-logic/smoke/build/environment'), '--output',
                        str(ROOT / 'build-logic/smoke/build/compiler output')] + ([] if web else ['--native-only']), check=True)
    else:
        result = gradle('-p', 'build-logic/smoke', 'prepareEmscripten', capture_output=True, text=True)
        output = ROOT / 'build-logic/smoke/build/unsupported.log'
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(result.stdout + result.stderr)
        assert result.returncode != 0 and 'no native Windows ARM64 payload' in output.read_text(), output.read_text()


if __name__ == '__main__':
    main()
