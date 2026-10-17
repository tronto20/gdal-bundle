#!/usr/bin/env python3
import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Optional, Set


def run(cmd):
    subprocess.run(cmd, check=True)


def is_macos() -> bool:
    return sys.platform == "darwin"


def is_linux() -> bool:
    return sys.platform.startswith("linux")


def is_windows() -> bool:
    return os.name == "nt" or sys.platform.startswith("win")


def library_name_candidates() -> List[str]:
    if is_macos():
        return ["libgdalalljni.dylib"]
    if is_linux():
        return ["libgdalalljni.so"]
    return ["gdalalljni.dll", "libgdalalljni.dll"]


def candidate_library_dirs(conda_prefix: Path) -> List[Path]:
    candidates = []
    if is_windows():
        candidates.extend(
            [
                conda_prefix / "Library" / "bin",
                conda_prefix / "bin",
                conda_prefix / "Library" / "lib",
            ]
        )
    else:
        candidates.extend([conda_prefix / "lib", conda_prefix / "bin"])
    return [path for path in candidates if path.exists()]


def candidate_share_dirs(conda_prefix: Path) -> List[Path]:
    candidates = [
        conda_prefix / "share",
        conda_prefix / "Library" / "share",
    ]
    return [path for path in candidates if path.exists()]


def candidate_java_dirs(conda_prefix: Path) -> List[Path]:
    candidates = []
    for base in candidate_share_dirs(conda_prefix):
        candidates.extend(
            [
                base / "java",
                base / "gdal" / "java",
                base / "gdal" / "share" / "java",
            ]
        )
    return [path for path in candidates if path.exists()]


def candidate_plugin_dirs(conda_prefix: Path) -> List[Path]:
    candidates = []
    for base in candidate_library_dirs(conda_prefix) + candidate_share_dirs(conda_prefix):
        candidates.extend(
            [
                base / "gdalplugins",
                base / "gdal" / "plugins",
                base / "gdal" / "gdalplugins",
            ]
        )
    return [path for path in candidates if path.exists()]


def find_first_existing(paths: List[Path]) -> Optional[Path]:
    return next((path for path in paths if path.exists()), None)


def find_jni_library(conda_prefix: Path) -> Optional[Path]:
    candidates = []
    for directory in candidate_library_dirs(conda_prefix):
        for name in library_name_candidates():
            candidates.append(directory / name)
            candidates.extend(directory.rglob(name))
    return find_first_existing(candidates)


def is_system_dep(dep: str) -> bool:
    return dep.startswith("/System/") or dep.startswith("/usr/lib/") or dep.startswith("/lib/")


def resolve_macos_dep(dep: str, conda_lib_dir: Path, current_file: Path) -> Optional[Path]:
    if dep.startswith("@rpath/"):
        return conda_lib_dir / dep[len("@rpath/") :]
    if dep.startswith("@loader_path/"):
        return current_file.parent / dep[len("@loader_path/") :]
    if dep.startswith("@executable_path/"):
        return None
    if dep.startswith("/"):
        return Path(dep)
    return None


def macos_deps(path: Path) -> List[str]:
    output = subprocess.check_output(["otool", "-L", str(path)], text=True)
    lines = output.splitlines()[1:]
    deps = []
    for line in lines:
        line = line.strip()
        if not line:
            continue
        deps.append(line.split(" ", 1)[0])
    return deps


def linux_deps(path: Path) -> List[str]:
    output = subprocess.check_output(["ldd", str(path)], text=True)
    deps = []
    for line in output.splitlines():
        line = line.strip()
        if not line or "statically linked" in line:
            continue
        if "=>" in line:
            _, right = line.split("=>", 1)
            resolved = right.strip().split(" ", 1)[0]
            if resolved and resolved != "not" and resolved != "not found" and resolved.startswith("/"):
                deps.append(resolved)
        else:
            token = line.split(" ", 1)[0]
            if token.startswith("/"):
                deps.append(token)
    return deps


def platform_deps(path: Path) -> List[str]:
    if is_macos():
        return macos_deps(path)
    if is_linux():
        return linux_deps(path)
    return []


def resolve_dep(dep: str, conda_prefix: Path, current_file: Path) -> Optional[Path]:
    if is_macos():
        return resolve_macos_dep(dep, conda_prefix / "lib", current_file)
    if is_linux():
        candidate = Path(dep)
        if candidate.exists() and candidate.is_file() and is_under(candidate.resolve(), conda_prefix):
            return candidate
        return None
    return None


def is_under(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def copy_files(file_map: Dict[str, Path], dest_dir: Path):
    dest_dir.mkdir(parents=True, exist_ok=True)
    for name, src in sorted(file_map.items()):
        dest_path = dest_dir / name
        if dest_path.exists() or dest_path.is_symlink():
            dest_path.unlink()
        shutil.copy2(src, dest_path)
        dest_path.chmod(dest_path.stat().st_mode | 0o200)


def create_symlinks(symlink_map: Dict[str, str], dest_dir: Path):
    if not symlink_map:
        return
    dest_dir.mkdir(parents=True, exist_ok=True)
    for name, target_name in sorted(symlink_map.items()):
        link_path = dest_dir / name
        if link_path.exists() or link_path.is_symlink():
            link_path.unlink()
        link_path.symlink_to(target_name)


def fix_install_names(file_path: Path, location: str, locations: Dict[str, str]):
    for dep in macos_deps(file_path):
        base = os.path.basename(dep)
        target_location = locations.get(base)
        if not target_location:
            continue
        if location == "lib":
            new = (
                f"@loader_path/{base}"
                if target_location == "lib"
                else f"@loader_path/../gdalplugins/{base}"
            )
        else:
            new = (
                f"@loader_path/{base}"
                if target_location == "gdalplugins"
                else f"@loader_path/../lib/{base}"
            )
        if dep != new:
            run(["install_name_tool", "-change", dep, new, str(file_path)])
    run(["install_name_tool", "-id", f"@loader_path/{file_path.name}", str(file_path)])


def codesign_files(paths: List[Path], identity: str):
    for path in sorted(paths):
        run(
            [
                "codesign",
                "--force",
                "--options",
                "runtime",
                "--timestamp",
                "--sign",
                identity,
                str(path),
            ]
        )


def is_shared_library_file(path: Path) -> bool:
    name = path.name.lower()
    return name.endswith(".dylib") or name.endswith(".dll") or ".so" in name


def collect_windows_libraries(conda_prefix: Path, lib_out: Path, plugin_out: Path) -> None:
    lib_files: Dict[str, Path] = {}
    plugin_files: Dict[str, Path] = {}

    for directory in candidate_library_dirs(conda_prefix):
        for file in directory.glob("*.dll"):
            lib_files.setdefault(file.name, file)

    for directory in candidate_plugin_dirs(conda_prefix):
        for file in directory.glob("*.dll"):
            plugin_files.setdefault(file.name, file)

    copy_files(lib_files, lib_out)
    if plugin_files:
        copy_files(plugin_files, plugin_out)


def collect_shared_libraries(conda_prefix: Path, bundle_root: Path, conda_lib_dir: Path):
    lib_out = bundle_root / "lib"
    plugin_out = bundle_root / "gdalplugins"
    plugin_dirs = candidate_plugin_dirs(conda_prefix)
    lib_files: Dict[str, Path] = {}
    lib_symlinks: Dict[str, str] = {}
    plugin_files: Dict[str, Path] = {}
    plugin_symlinks: Dict[str, str] = {}
    scan_queue: List[Path] = []
    scanned: Set[Path] = set()

    def add_file(path: Path, dest: str):
        if not path.exists():
            return
        original_name = path.name
        if path.is_symlink():
            target = path.resolve()
            if dest == "lib":
                lib_symlinks[original_name] = target.name
            else:
                plugin_symlinks[original_name] = target.name
            path = target
        if dest == "lib":
            lib_files.setdefault(path.name, path)
        else:
            plugin_files.setdefault(path.name, path)
        if path not in scanned and (dest == "lib" or is_shared_library_file(path)):
            scanned.add(path)
            scan_queue.append(path)

    jni_lib = find_jni_library(conda_prefix)
    if not jni_lib:
        candidates = ", ".join(str(path) for path in candidate_library_dirs(conda_prefix))
        raise SystemExit(
            "GDAL JNI library not found. Searched library directories: " + candidates
        )
    add_file(jni_lib, "lib")

    gdal_core_candidates = [directory / "libgdal.dylib" for directory in candidate_library_dirs(conda_prefix)]
    gdal_core_candidates.extend([directory / "libgdal.so" for directory in candidate_library_dirs(conda_prefix)])
    gdal_core_candidates.extend([directory / "gdal.dll" for directory in candidate_library_dirs(conda_prefix)])
    gdal_core = find_first_existing(gdal_core_candidates)
    if gdal_core:
        add_file(gdal_core, "lib")

    for plugin_dir in plugin_dirs:
        for file in plugin_dir.glob("*"):
            if file.is_file():
                add_file(file, "gdalplugins")

    while scan_queue:
        current = scan_queue.pop(0)
        for dep in platform_deps(current):
            if is_macos() and is_system_dep(dep):
                continue
            candidate = resolve_dep(dep, conda_prefix, current)
            if not candidate or not candidate.exists():
                continue
            resolved = candidate.resolve()
            if plugin_dirs and any(is_under(resolved, plugin_dir) for plugin_dir in plugin_dirs):
                add_file(candidate, "gdalplugins")
            else:
                add_file(candidate, "lib")

    copy_files(lib_files, lib_out)
    create_symlinks(lib_symlinks, lib_out)

    if plugin_files or plugin_symlinks:
        copy_files(plugin_files, plugin_out)
        create_symlinks(plugin_symlinks, plugin_out)

    return lib_out, plugin_out


def copy_data_dirs(conda_prefix: Path, bundle_root: Path):
    share_root = bundle_root / "share"
    java_data = find_first_existing(candidate_java_dirs(conda_prefix))
    gdal_data = find_first_existing([directory / "gdal" for directory in candidate_share_dirs(conda_prefix)])
    proj_data = find_first_existing([directory / "proj" for directory in candidate_share_dirs(conda_prefix)])
    if java_data:
        shutil.copytree(java_data, share_root / "java", dirs_exist_ok=True)
    else:
        for java_dir in candidate_java_dirs(conda_prefix):
            for jar in java_dir.glob("*.jar"):
                share_root.joinpath("java").mkdir(parents=True, exist_ok=True)
                shutil.copy2(jar, share_root / "java" / jar.name)
    if gdal_data:
        shutil.copytree(gdal_data, share_root / "gdal", dirs_exist_ok=True)
    if proj_data:
        shutil.copytree(proj_data, share_root / "proj", dirs_exist_ok=True)


def main():
    parser = argparse.ArgumentParser(
        description="Bundle GDAL JNI libraries and data from a conda environment."
    )
    parser.add_argument("--conda-prefix", required=False, help="Conda environment prefix")
    parser.add_argument("--output-dir", required=True, help="Output root directory")
    parser.add_argument(
        "--codesign-identity",
        required=False,
        help="Codesign identity for bundled dylibs (macOS only, or set CODESIGN_IDENTITY).",
    )
    args = parser.parse_args()

    conda_prefix = Path(args.conda_prefix or os.environ.get("CONDA_PREFIX", ""))
    if not conda_prefix:
        raise SystemExit("CONDA_PREFIX is required (env var or --conda-prefix).")
    if not conda_prefix.exists():
        raise SystemExit(f"Conda prefix not found: {conda_prefix}")

    out_root = Path(args.output_dir).resolve()
    bundle_root = out_root / "gdal"
    bundle_root.mkdir(parents=True, exist_ok=True)

    if is_windows():
        collect_windows_libraries(conda_prefix, bundle_root / "lib", bundle_root / "gdalplugins")
    else:
        conda_lib_dir = find_first_existing(candidate_library_dirs(conda_prefix))
        if not conda_lib_dir:
            raise SystemExit(
                "Conda library directory not found. Searched: "
                + ", ".join(str(path) for path in candidate_library_dirs(conda_prefix))
            )
        lib_out, plugin_out = collect_shared_libraries(conda_prefix, bundle_root, conda_lib_dir)
        if is_macos():
            locations = {}
            for path in lib_out.glob("*"):
                if path.is_file() and is_shared_library_file(path):
                    locations[path.name] = "lib"
            for path in plugin_out.glob("*"):
                if path.is_file() and is_shared_library_file(path):
                    locations[path.name] = "gdalplugins"
            for path in lib_out.glob("*"):
                if path.is_file() and is_shared_library_file(path):
                    fix_install_names(path, "lib", locations)
            for path in plugin_out.glob("*"):
                if path.is_file() and is_shared_library_file(path):
                    fix_install_names(path, "gdalplugins", locations)
            identity = args.codesign_identity or os.environ.get("CODESIGN_IDENTITY", "") or "-"
            codesign_files(
                [
                    path
                    for path in list(lib_out.glob("*")) + list(plugin_out.glob("*"))
                    if path.is_file() and is_shared_library_file(path)
                ],
                identity,
            )

    copy_data_dirs(conda_prefix, bundle_root)

    print(f"Bundled GDAL into: {bundle_root}")


if __name__ == "__main__":
    main()
