"""Coordinate policy for independently selected GDAL engine publications."""
import xml.etree.ElementTree as ET
from jvm_binaries import JVM_BINARIES

LIBRARY_TARGETS = ['jvm','android','js','wasm-js','macosarm64','linuxx64','linuxarm64','mingwx64','iosarm64','iossimulatorarm64']
ENGINE_TARGETS = ['js','wasm-js','macosarm64','linuxx64','linuxarm64','mingwx64','iosarm64','iossimulatorarm64']
JVM_PAYLOADS = {'gdal4k-binary-'+b for b in JVM_BINARIES}


def is_binary_module(module):
    return module == 'gdal4k-android-runtime' or module == 'gdal4k-engine' or module.startswith('gdal4k-engine-') or module in JVM_PAYLOADS


def expected_coordinates():
    return {m+s for m in ['gdal4k','gdal4k-runtime','gdal4k-binary'] for s in ['', *['-'+t for t in LIBRARY_TARGETS]]} | {'gdal4k-engine'+s for s in ['', *['-'+t for t in ENGINE_TARGETS]]} | {'gdal4k-android-runtime'} | JVM_PAYLOADS


def library_version_from_repo(repo):
    poms = list((repo/'dev/tronto/gdal4k/gdal4k').glob('*/*.pom'))
    if len(poms) != 1:
        raise ValueError('Expected exactly one tested gdal4k library version in '+str(repo))
    version = ET.parse(poms[0]).findtext('{http://maven.apache.org/POM/4.0.0}version')
    if not version:
        raise ValueError('Missing gdal4k library version')
    return version


def validate_versions(items):
    library_versions = [item['coordinate'][2] for item in items if item['coordinate'][1] == 'gdal4k']
    if len(library_versions) != 1:
        raise ValueError('Expected exactly one gdal4k root coordinate')
    library_version = library_versions[0]
    for item in items:
        group, module, version = item['coordinate']
        expected = library_version
        if group != 'dev.tronto.gdal4k' or version != expected:
            raise ValueError(f'Unexpected coordinate version: {group}:{module}:{version}; expected {expected}')
    return library_version
