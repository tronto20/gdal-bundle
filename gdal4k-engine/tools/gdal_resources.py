"""The complete pinned GDAL resource set, shared by native and web packaging."""
def resource_files(prefix):
    root = prefix / 'share/gdal'
    files = [f for f in sorted(root.rglob('*'), key=lambda f: f.relative_to(root).as_posix())
             if f.is_file() and f.suffix != '.cmake' and not f.name.startswith('vcpkg') and f.name not in ('copyright', 'usage')]
    if not any(f.name == 'tms_NZTM2000.json' for f in files):
        raise RuntimeError('The complete installed GDAL data files are required')
    return files
