"""Producer-only toolchain configuration; published consumers use embedded artifacts."""
import os
import platform
import subprocess
from pathlib import Path

KOTLIN_VERSION = '2.4.21'

def konan_home():
    configured = os.environ.get('KONAN_HOME')
    if configured:
        return Path(configured)
    host = {'Darwin': 'macos', 'Linux': 'linux', 'Windows': 'windows'}[platform.system()]
    arch = 'aarch64' if platform.machine().lower() in ('aarch64', 'arm64') else 'x86_64'
    directory = Path(os.environ.get('KONAN_DATA_DIR', Path.home()/'.konan'))
    home = directory/f'kotlin-native-prebuilt-{host}-{arch}-{KOTLIN_VERSION}'
    if not home.exists():
        raise RuntimeError(f'Initialize the pinned Kotlin/Native compiler first with a gdal4k-runtime native compilation task; expected {home}')
    return home

def native_toolchain(target, destination):
    """Use the consumer's C++ ABI and Linux sysroot, not the producer OS's glibc."""
    home = konan_home()
    konan_target = 'mingw_x64' if target.startswith('windows') else 'linux_arm64' if target.endswith('arm64') else 'linux_x64'
    compiler = home/'bin'/('konanc.bat' if os.name == 'nt' else 'konanc')
    subprocess.run([str(compiler), '-Xcheck-dependencies', '-target', konan_target], check=True)
    dependencies = Path(os.environ.get('KONAN_DATA_DIR', Path.home()/'.konan'))/'dependencies'
    if target.startswith('windows'):
        if os.name != 'nt':
            raise RuntimeError('Build the Windows engine on Windows with the Kotlin/Native MinGW toolchain')
        gcc = dependencies/'msys2-mingw-w64-x86_64-2'
        os.environ['PATH'] = str(gcc/'bin')+os.pathsep+os.environ['PATH']
        llvm = next(p for p in dependencies.glob('llvm-21-x86_64-windows-*') if (p/'bin/clang.exe').exists())
        # K/N's stripped GCC distribution has headers/runtime but no cc1plus.
        # Use its LLVM compiler and the matching MinGW sysroot, as K/N itself does.
        text = f'''set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_C_COMPILER "{(llvm/'bin/clang.exe').as_posix()}")
set(CMAKE_CXX_COMPILER "{(llvm/'bin/clang++.exe').as_posix()}")
set(CMAKE_C_COMPILER_TARGET x86_64-w64-windows-gnu)
set(CMAKE_CXX_COMPILER_TARGET x86_64-w64-windows-gnu)
set(CMAKE_SYSROOT "{gcc.as_posix()}")
set(CMAKE_EXE_LINKER_FLAGS_INIT "-fuse-ld={llvm.as_posix()}/bin/ld.lld.exe")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "-fuse-ld={llvm.as_posix()}/bin/ld.lld.exe")
set(CMAKE_RC_COMPILER "{(gcc/'bin/windres.exe').as_posix()}")
set(CMAKE_AR "{(llvm/'bin/llvm-ar.exe').as_posix()}")
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
'''
    else:
        arch = 'aarch64' if target.endswith('arm64') else 'x86_64'
        version = '2.25' if arch == 'aarch64' else '2.19'
        gcc = dependencies/f'{arch}-unknown-linux-gnu-gcc-8.3.0-glibc-{version}-kernel-4.9-2'
        host = 'macos' if platform.system() == 'Darwin' else 'linux'
        host_arch = 'aarch64' if platform.machine().lower() in ('aarch64','arm64') else 'x86_64'
        llvm = next(p for p in dependencies.glob(f'llvm-21-{host_arch}-{host}-*') if (p/'bin/clang').exists())
        sysroot = gcc/f'{arch}-unknown-linux-gnu/sysroot'
        text = f'''set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR {arch})
set(CMAKE_C_COMPILER "{(llvm/'bin/clang').as_posix()}")
set(CMAKE_CXX_COMPILER "{(llvm/'bin/clang++').as_posix()}")
set(CMAKE_C_COMPILER_TARGET {arch}-unknown-linux-gnu)
set(CMAKE_CXX_COMPILER_TARGET {arch}-unknown-linux-gnu)
set(CMAKE_SYSROOT "{sysroot.as_posix()}")
set(CMAKE_C_FLAGS_INIT "--gcc-toolchain={gcc.as_posix()}")
set(CMAKE_CXX_FLAGS_INIT "--gcc-toolchain={gcc.as_posix()}")
set(CMAKE_EXE_LINKER_FLAGS_INIT "-fuse-ld={llvm.as_posix()}/bin/ld.lld")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "-fuse-ld={llvm.as_posix()}/bin/ld.lld")
set(CMAKE_AR "{(llvm/'bin/llvm-ar').as_posix()}")
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
'''
    licenses = destination.parent/'toolchain-licenses'
    licenses.mkdir(parents=True,exist_ok=True)
    roots = [gcc/'share/licenses/gcc-libs',gcc/'share/licenses/gcc',gcc/'share/licenses/libwinpthread']
    for root in roots:
        if root.exists():
            for name in ['COPYING.RUNTIME','COPYING3','COPYING.LIB','COPYING']:
                source = root/name
                if source.is_file():
                    (licenses/(root.name+'-'+name)).write_bytes(source.read_bytes())
    destination.write_text(text)
    return destination
