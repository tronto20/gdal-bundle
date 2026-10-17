"""Regression cases for engine payload isolation across commonMain dependencies."""
import copy
import importlib.util
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT/'gdal4k-engine/tools'))
from engine_variants import add_empty_variants, target_of, TARGETS, COORDINATES
spec = importlib.util.spec_from_file_location('check_engine_variants', ROOT/'verification/check-engine-variants.py')
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)


class EngineVariantsTest(unittest.TestCase):
    def test_each_target_preserves_its_payload_and_adds_no_cross_target_files(self):
        for target, attrs in TARGETS.items():
            with self.subTest(target=target):
                original = {'name': 'realApi', 'attributes': {'org.gradle.category': 'library', 'org.gradle.usage': 'java-api' if target in ['jvm', 'android'] else 'kotlin-api', **attrs}, 'files': [{'name': 'engine', 'url': 'engine', 'sha256': 'tested-fixture-hash'}]}
                module = 'gdal4k-engine-android-arm64' if target == 'android' else 'gdal4k-engine-fixture'
                metadata = add_empty_variants({'component': {'module': module}, 'variants': [copy.deepcopy(original)]})
                self.assertEqual(metadata['variants'][0], original)
                self.assertEqual({target_of(v) for v in metadata['variants']}, {target})
                checks.check(metadata, {target})
                self.assertEqual(add_empty_variants(copy.deepcopy(metadata)), metadata)

    def test_agp_aar_gets_android_attributes_without_mutating_empty_variants_on_repeat(self):
        metadata = {'component': {'module': 'gdal4k-engine-android-arm64'}, 'variants': [{'name': 'releaseRuntimeElements', 'attributes': {'org.gradle.category': 'library', 'org.gradle.usage': 'java-runtime'}, 'files': [{'name': 'engine.aar'}]}]}
        add_empty_variants(metadata)
        self.assertEqual(target_of(metadata['variants'][0]), 'android')
        self.assertEqual(add_empty_variants(copy.deepcopy(metadata)), metadata)
        checks.check(metadata, {'android'})

    def test_root_adds_android_redirect_and_empty_jvm_without_changing_native_variants(self):
        metadata = {'component': {'group': 'dev.tronto.gdal4k', 'module': 'gdal4k-engine', 'version': '0.1.0-SNAPSHOT'}, 'variants': [{'name': 'macosArm64Api', 'attributes': {'org.gradle.category': 'library', 'org.gradle.usage': 'kotlin-api', **TARGETS['macosArm64']}, 'available-at': {'module': 'gdal4k-engine-native-macos-arm64'}}]}
        metadata['variants'][0]['available-at'].update(group='dev.tronto.gdal4k', version='0.1.0-SNAPSHOT', url='../../gdal4k-engine-native-macos-arm64/0.1.0-SNAPSHOT/gdal4k-engine-native-macos-arm64-0.1.0-SNAPSHOT.module')
        original = copy.deepcopy(metadata['variants'][0])
        add_empty_variants(metadata)
        self.assertEqual(metadata['variants'][0], original)
        self.assertEqual(add_empty_variants(copy.deepcopy(metadata)), metadata)
        jvm = [v for v in metadata['variants'] if target_of(v) == 'jvm']
        self.assertEqual(len(jvm), 2)
        for variant in jvm:
            self.assertFalse(any(variant.get(key) for key in ['files', 'dependencies', 'available-at']))
        android = [v for v in metadata['variants'] if target_of(v) == 'android']
        self.assertEqual(len(android), 2)
        self.assertTrue(all(v['available-at']['module'] == 'gdal4k-engine-android-arm64' for v in android))

    def test_native_publication_with_canonical_root_identity_is_not_a_root_selector(self):
        metadata = {'component': {'group': 'dev.tronto.gdal4k', 'module': 'gdal4k-engine', 'version': '0.1.0-SNAPSHOT'}, 'variants': [{'name': 'nativeApi', 'attributes': {'org.gradle.category': 'library', 'org.gradle.usage': 'kotlin-api', **TARGETS['macosArm64']}, 'files': [{'name': 'engine.klib'}]}]}
        original = copy.deepcopy(metadata)
        self.assertEqual(add_empty_variants(metadata, 'gdal4k-engine-native-macos-arm64'), original)
        checks.check(metadata, {'macosArm64'}, 'gdal4k-engine-native-macos-arm64')

    def test_disabled_host_targets_redirect_to_published_coordinates(self):
        component = {'group': 'dev.tronto.gdal4k', 'module': 'gdal4k-engine', 'version': '0.1.0-SNAPSHOT'}
        variants = [{'name': 'metadataApi', 'attributes': {'org.gradle.category': 'library', 'org.jetbrains.kotlin.platform.type': 'common'}}]
        for target, coordinate in COORDINATES.items():
            for category in ['library', 'documentation']:
                default = 'gdal4k-engine-' + target.lower()
                variants.append({'name': target + category, 'attributes': {'org.gradle.category': category, **TARGETS[target]},
                                 'available-at': {**component, 'module': default, 'url': '../../' + default + '/old.module'}})
        metadata = {'component': component, 'variants': variants}
        with self.assertRaisesRegex(ValueError, 'Invalid .* engine redirect'):
            checks.check(metadata, set(COORDINATES))
        add_empty_variants(metadata)
        checks.check(metadata, set(COORDINATES))
        for variant in variants:
            if 'available-at' in variant:
                redirect = variant['available-at']
                coordinate = COORDINATES[target_of(variant)]
                self.assertEqual(redirect['module'], coordinate)
                self.assertEqual(redirect['url'], f'../../{coordinate}/0.1.0-SNAPSHOT/{coordinate}-0.1.0-SNAPSHOT.module')
        self.assertEqual(add_empty_variants(copy.deepcopy(metadata)), metadata)

    def test_non_engine_coordinate_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Not an engine coordinate'):
            add_empty_variants({'component': {'module': 'gdal4k-runtime'}, 'variants': []})


if __name__ == '__main__':
    unittest.main()
