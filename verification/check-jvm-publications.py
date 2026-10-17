#!/usr/bin/env python3
"""Reject implicit payload dependencies and mixed/missing resource-only JARs."""
import argparse
import hashlib
import json
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from jvm_binaries import JVM_BINARIES

ROOT = Path(__file__).resolve().parent.parent
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--repo', type=Path, default=ROOT/'verification/build/consumer-maven')
p.add_argument('--binary', action='append', choices=JVM_BINARIES, help='Check a local subset; CI checks all five')
a = p.parse_args()
base = a.repo.resolve()/'dev/tronto/gdal4k'
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
for module in ['gdal4k', 'gdal4k-runtime', 'gdal4k-binary']:
    for suffix in ['', '-jvm']:
        directory = base/(module+suffix)
        poms = list(directory.glob('*/*.pom'))
        metadata_files = list(directory.glob('*/*.module'))
        if len(poms) != 1 or len(metadata_files) != 1: raise RuntimeError('Missing JVM dependency descriptors: '+str(directory))
        for pom in poms:
            for artifact in ET.parse(pom).findall('.//m:dependency/m:artifactId', ns):
                if artifact.text in {'gdal4k-binary-'+b for b in JVM_BINARIES}:
                    raise RuntimeError('Implicit JVM payload dependency: '+str(pom))
        for metadata in metadata_files:
            data = json.loads(metadata.read_text())
            for variant in data['variants']:
                for dependency in variant.get('dependencies', []):
                    if dependency['module'] in {'gdal4k-binary-'+b for b in JVM_BINARIES}:
                        raise RuntimeError('Implicit JVM payload dependency: '+str(metadata))
    jars = list((base/(module+'-jvm')).glob('*/*-SNAPSHOT.jar'))
    if len(jars) != 1: raise RuntimeError('Missing JVM implementation JAR: '+module)
    with zipfile.ZipFile(jars[0]) as archive:
        if any(n.endswith(('.so', '.dylib', '.dll')) or n.startswith('gdal4k/') for n in archive.namelist()):
            raise RuntimeError('JVM implementation contains a native payload: '+str(jars[0]))
for suffix in a.binary or JVM_BINARIES:
    _, platform, filename = JVM_BINARIES[suffix]
    directory = base/('gdal4k-binary-'+suffix)
    jars = list(directory.glob('*/*-SNAPSHOT.jar'))
    if len(jars) != 1: raise RuntimeError('Missing resource JAR: '+suffix)
    resource = f'gdal4k/{platform}/{filename}'
    original = ROOT/'gdal4k-binary/build/jvm-resources'/resource
    with zipfile.ZipFile(jars[0]) as archive:
        files = {n for n in archive.namelist() if not n.endswith('/') and n != 'META-INF/MANIFEST.MF'}
        if files != {resource}: raise RuntimeError(f'Unexpected payload files in {suffix}: {files}')
        if hashlib.sha256(archive.read(resource)).digest() != hashlib.sha256(original.read_bytes()).digest():
            raise RuntimeError('Resource JAR differs from verified engine: '+suffix)
    poms = list(directory.glob('*/*.pom'))
    if len(poms) != 1 or ET.parse(poms[0]).findall('.//m:dependency', ns):
        raise RuntimeError('Payload must not bring other payloads or implementation dependencies: '+suffix)
print('JVM implementation contains no default binary; resource publications contain exactly their selected payload.')
