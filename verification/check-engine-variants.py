#!/usr/bin/env python3
"""Check commonMain engine selectors retain payloads only on their intended target."""
import argparse
import json
import sys
from pathlib import Path
from publication_versions import ANDROID_ENGINE, ENGINE_COORDINATES

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT/'gdal4k-engine/tools'))
from engine_variants import COORDINATES, TARGETS, target_of


def check(metadata, expected_targets, module=None):
    variants = metadata['variants']
    if (module or metadata['component']['module']) == 'gdal4k-engine':
        for variant in variants:
            redirect = variant.get('available-at')
            target = target_of(variant)
            if redirect and target in COORDINATES:
                coordinate = COORDINATES[target]
                version = metadata['component']['version']
                expected_url = f'../../{coordinate}/{version}/{coordinate}-{version}.module'
                if (redirect.get('module') != coordinate or redirect.get('url') != expected_url
                        or redirect.get('group') != metadata['component']['group']
                        or redirect.get('version') != version):
                    raise ValueError(f'Invalid {target} engine redirect: {redirect}')
    library = [v for v in variants if v['attributes'].get('org.gradle.category') == 'library']
    if (module or metadata['component']['module']) == 'gdal4k-engine' and not (set(TARGETS) | {'common'}) <= {target_of(v) for v in library}:
        raise ValueError('Incomplete engine target coverage: ' + metadata['component']['module'])
    actual = set()
    for variant in library:
        if variant['name'].startswith('empty'):
            if any(variant.get(key) for key in ['files', 'dependencies', 'available-at', 'dependencyConstraints']):
                raise ValueError('Empty engine variant contains artifacts/dependencies: ' + variant['name'])
        elif target_of(variant) != 'common':
            actual.add(target_of(variant))
    if actual != set(expected_targets):
        raise ValueError(f'Unexpected payload targets: {actual}; expected {expected_targets}')
    return len(variants)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--repo', type=Path, required=True)
    p.add_argument('--partial', action='store_true')
    a = p.parse_args()
    coordinates = {module: 'wasmJs' if target == 'wasm-js' else {
        'macosarm64': 'macosArm64', 'linuxx64': 'linuxX64', 'linuxarm64': 'linuxArm64',
        'mingwx64': 'mingwX64', 'iosarm64': 'iosArm64', 'iossimulatorarm64': 'iosSimulatorArm64',
    }.get(target, target) for target, module in ENGINE_COORDINATES.items()}
    expected = {module: {target} for module, target in coordinates.items()}
    expected[ANDROID_ENGINE] = {'android'}
    expected['gdal4k-engine'] = set(coordinates.values()) | {'android'}
    checked = 0
    for module, targets in expected.items():
        directory = a.repo.resolve()/'dev/tronto/gdal4k'/module
        if not directory.exists() and a.partial:
            continue
        files = list(directory.glob('*/*.module'))
        if len(files) != 1:
            raise ValueError('Expected one Gradle metadata file: ' + module)
        check(json.loads(files[0].read_text()), targets, module)
        checked += 1
    print(f'Checked {checked} engine metadata files: exact payload targets; no default JVM binary.')


if __name__ == '__main__':
    main()
