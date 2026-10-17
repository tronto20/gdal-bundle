"""Route the common engine root to platform payloads and keep JVM classifiers opt-in."""
import argparse
import json
from pathlib import Path

TARGETS = {
    'jvm': {'org.jetbrains.kotlin.platform.type': 'jvm', 'org.gradle.jvm.environment': 'standard-jvm', 'org.gradle.libraryelements': 'jar'},
    'android': {'org.jetbrains.kotlin.platform.type': 'androidJvm', 'org.gradle.jvm.environment': 'android', 'org.gradle.libraryelements': 'aar'},
    'js': {'org.jetbrains.kotlin.platform.type': 'js', 'org.jetbrains.kotlin.js.compiler': 'ir', 'org.gradle.jvm.environment': 'non-jvm'},
    'wasmJs': {'org.jetbrains.kotlin.platform.type': 'wasm', 'org.jetbrains.kotlin.wasm.target': 'js', 'org.gradle.jvm.environment': 'non-jvm'},
    **{name: {'org.jetbrains.kotlin.platform.type': 'native', 'org.jetbrains.kotlin.native.target': target, 'org.gradle.jvm.environment': 'non-jvm'} for name, target in {
        'macosArm64': 'macos_arm64', 'linuxX64': 'linux_x64', 'linuxArm64': 'linux_arm64',
        'mingwX64': 'mingw_x64', 'iosArm64': 'ios_arm64', 'iosSimulatorArm64': 'ios_simulator_arm64',
    }.items()},
}


def target_of(variant):
    attrs = variant['attributes']
    platform = attrs.get('org.jetbrains.kotlin.platform.type')
    if platform == 'jvm':
        return 'android' if attrs.get('org.gradle.jvm.environment') == 'android' else 'jvm'
    if platform == 'androidJvm':
        return 'android'
    if platform == 'wasm':
        return 'wasmJs' if attrs.get('org.jetbrains.kotlin.wasm.target') == 'js' else None
    if platform == 'native':
        return next((name for name, target in TARGETS.items() if target.get('org.jetbrains.kotlin.native.target') == attrs.get('org.jetbrains.kotlin.native.target')), None)
    return platform


def add_empty_variants(metadata, module=None):
    """Preserve real variants, route Android, and add an artifact-free JVM choice.

    No new Maven coordinates or placeholder JAR/KLIBs are necessary. In particular,
    JNI JARs still contain exactly their selected OS payload and no Kotlin API.
    """
    # KMP target metadata identifies its root component; use the publication ID.
    module = module or metadata['component']['module']
    if not (module == 'gdal4k-engine' or module.startswith('gdal4k-engine-')):
        raise ValueError('Not an engine coordinate: ' + module)
    variants = metadata['variants']
    if module == 'gdal4k-engine-android-arm64':
        # AGP's standalone AAR does not publish Kotlin platform attributes itself.
        for variant in variants:
            attrs = variant['attributes']
            if attrs.get('org.gradle.category') == 'library' and not variant['name'].startswith('empty'):
                attrs.update(TARGETS['android'])
    # The root selector also routes Android to its independently produced AAR.
    if module == 'gdal4k-engine' and 'android' not in {target_of(v) for v in variants if not v['name'].startswith('empty')}:
        variants[:] = [v for v in variants if not (v['name'].startswith('empty') and target_of(v) == 'android')]
        component = metadata['component']
        version = component['version']
        android_module = 'gdal4k-engine-android-arm64'
        for usage in ['java-api', 'java-runtime']:
            variants.append({'name': 'android' + usage.title().replace('-', ''), 'attributes': {
                'org.gradle.category': 'library', 'org.gradle.usage': usage, **TARGETS['android'],
            }, 'available-at': {
                'url': f'../../{android_module}/{version}/{android_module}-{version}.module',
                'group': component['group'], 'module': android_module, 'version': version,
            }})
    if module == 'gdal4k-engine':
        # JVM has no inherent OS/CPU target. A classifier must be selected explicitly.
        present = {target_of(v) for v in variants}
        if 'jvm' not in present:
            for usage in ['java-api', 'java-runtime']:
                variants.append({'name': 'emptyJvm' + usage.title().replace('-', ''), 'attributes': {
                    'org.gradle.category': 'library', 'org.gradle.usage': usage, **TARGETS['jvm'],
                }})
    return metadata


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('metadata', type=Path)
    p.add_argument('--module', required=True)
    a = p.parse_args()
    metadata = json.loads(a.metadata.read_text())
    a.metadata.write_text(json.dumps(add_empty_variants(metadata, a.module), indent=2) + '\n')


if __name__ == '__main__':
    main()
