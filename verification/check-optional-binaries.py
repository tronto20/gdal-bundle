#!/usr/bin/env python3
"""Reject engine payloads and implicit engine dependencies on every library target."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile
from publication_versions import expected_coordinates, is_binary_module, validate_versions

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--repo', type=Path, required=True)
p.add_argument('--partial', action='store_true', help='Check a local publication subset')
a = p.parse_args()
base = a.repo.resolve()/'dev/tronto/gdal4k'
items = []
checked = 0
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
for directory in sorted(base.iterdir()):
    poms = list(directory.glob('*/*.pom'))
    if len(poms) != 1:
        raise RuntimeError('Expected one tested coordinate version: '+str(directory))
    xml = ET.parse(poms[0])
    coordinate = [xml.findtext('m:'+key, namespaces=ns) for key in ['groupId', 'artifactId', 'version']]
    items.append({'coordinate': coordinate})
    if is_binary_module(directory.name):
        continue
    for dependency in xml.findall('.//m:dependency', ns):
        if dependency.findtext('m:groupId', namespaces=ns) == 'dev.tronto.gdal4k' and is_binary_module(dependency.findtext('m:artifactId', namespaces=ns)):
            raise RuntimeError('Library POM includes an implicit engine: '+str(poms[0]))
    for metadata in directory.glob('*/*.module'):
        for variant in json.loads(metadata.read_text())['variants']:
            for dependency in variant.get('dependencies', []):
                if dependency['group'] == 'dev.tronto.gdal4k' and is_binary_module(dependency['module']):
                    raise RuntimeError('Library Gradle metadata includes an implicit engine: '+str(metadata))
    for artifact in directory.glob('*/*'):
        if artifact.suffix not in {'.jar', '.klib', '.aar'}:
            continue
        with zipfile.ZipFile(artifact) as archive:
            if any(name.endswith(('.a', '.so', '.dylib', '.dll', '.wasm')) or name.rsplit('/', 1)[-1] in {'gdal4k.mjs', 'gdal4k-worker.mjs', 'gdal4k-data.wasm'} for name in archive.namelist()):
                raise RuntimeError('Library artifact contains an engine payload: '+str(artifact))
        checked += 1
validate_versions(items)
if not a.partial and {item['coordinate'][1] for item in items} != expected_coordinates():
    raise RuntimeError('Incomplete or unexpected complete Maven repository')
print(f'Checked {checked} library archives: no engine payload or implicit binary dependency on any target.')
