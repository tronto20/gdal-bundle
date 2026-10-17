"""Explicit JVM payload coordinates shared by publication/consumer verification."""
import platform

# suffix -> (publication name, engine platform, resource filename)
JVM_BINARIES = {
    'macos-arm64': ('MacosArm64', 'macos-arm64', 'libgdal4k.dylib'),
    'macos-x64': ('MacosX64', 'macos-amd64', 'libgdal4k.dylib'),
    'linux-x64-gnu': ('LinuxX64Gnu', 'linux-amd64', 'libgdal4k.so'),
    'linux-arm64-gnu': ('LinuxArm64Gnu', 'linux-arm64', 'libgdal4k.so'),
    'windows-x64': ('WindowsX64', 'windows-amd64', 'gdal4k.dll'),
}

def host_binary():
    """Test-tool convenience only. The published library never selects a dependency."""
    system = platform.system()
    arch = platform.machine().lower()
    cpu = 'arm64' if arch in ['aarch64', 'arm64'] else 'x64' if arch in ['amd64', 'x86_64'] else None
    suffix = {'Darwin': 'macos', 'Linux': 'linux', 'Windows': 'windows'}.get(system, system) + '-' + str(cpu)
    if system == 'Linux': suffix += '-gnu'
    if suffix not in JVM_BINARIES: raise RuntimeError(f'Unsupported JVM consumer host: {system}/{arch}')
    return suffix
