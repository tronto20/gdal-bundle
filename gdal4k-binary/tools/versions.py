"""Read the engine package version shared with Gradle's binary publications."""
import os
from pathlib import Path


def configured_binary_version():
    properties = dict(line.strip().split('=', 1) for line in
                      (Path(__file__).resolve().parents[2]/'gradle.properties').read_text().splitlines()
                      if '=' in line and not line.lstrip().startswith('#'))
    return os.environ.get('GDAL4K_BINARY_VERSION') or properties['gdal4kVersion']
