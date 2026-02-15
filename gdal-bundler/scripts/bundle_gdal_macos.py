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


def otool_deps(path: Path):
    output = subprocess.check_output(["otool", "-L", str(path)], text=True)
    lines = output.splitlines()[1:]
    deps = []
    for line in lines:
        line = line.strip()
        if not line:
            continue
        deps.append(line.split(" ", 1)[0])
    return deps


def is_system_dep(dep: str) -> bool:
    return dep.startswith("/System/") or dep.startswith("/usr/lib/") or dep.startswith("/System/Library/")


def resolve_dep(dep: str, conda_lib_dir: Path, current_file: Path) -> Optional[Path]:
    if dep.startswith("@rpath/"):
        return conda_lib_dir / dep[len("@rpath/") :]
    if dep.startswith("@loader_path/"):
        return current_file.parent / dep[len("@loader_path/") :]
    if dep.startswith("@executable_path/"):
        return None
    if dep.startswith("/"):
        return Path(dep)
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
    for dep in otool_deps(file_path):
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


def main():
    parser = argparse.ArgumentParser(
        description="Bundle GDAL macOS dylibs and data from a conda environment."
    )
    parser.add_argument("--conda-prefix", required=False, help="Conda environment prefix")
    parser.add_argument("--output-dir", required=True, help="Output root directory")
    parser.add_argument(
        "--codesign-identity",
        required=False,
        help="Codesign identity for bundled dylibs (or set CODESIGN_IDENTITY).",
    )
    args = parser.parse_args()

    if sys.platform != "darwin":
        raise SystemExit("This script only supports macOS.")

    conda_prefix = Path(args.conda_prefix or os.environ.get("CONDA_PREFIX", ""))
    if not conda_prefix:
        raise SystemExit("CONDA_PREFIX is required (env var or --conda-prefix).")
    if not conda_prefix.exists():
        raise SystemExit(f"Conda prefix not found: {conda_prefix}")

    codesign_identity = args.codesign_identity or os.environ.get("CODESIGN_IDENTITY", "")

    conda_lib_dir = conda_prefix / "lib"
    if not conda_lib_dir.exists():
        raise SystemExit(f"Conda lib dir not found: {conda_lib_dir}")

    gdal_jni_candidates = [
        conda_lib_dir / "libgdalalljni.dylib",
        conda_lib_dir / "jni" / "libgdalalljni.dylib",
    ]
    gdal_jni = next((p for p in gdal_jni_candidates if p.exists()), None)
    if not gdal_jni:
        raise SystemExit(
            "GDAL JNI dylib not found. Checked: "
            + ", ".join(str(p) for p in gdal_jni_candidates)
        )

    gdal_core = conda_lib_dir / "libgdal.dylib"

    out_root = Path(args.output_dir).resolve()
    bundle_root = out_root / "gdal"
    lib_out = bundle_root / "lib"
    plugin_out = bundle_root / "gdalplugins"

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
        if path not in scanned:
            scanned.add(path)
            scan_queue.append(path)

    add_file(gdal_jni, "lib")
    if gdal_core.exists():
        add_file(gdal_core, "lib")

    plugin_dir = conda_lib_dir / "gdalplugins"
    if plugin_dir.exists():
        for dylib in sorted(plugin_dir.glob("*.dylib")):
            add_file(dylib, "gdalplugins")

    while scan_queue:
        current = scan_queue.pop(0)
        for dep in otool_deps(current):
            if is_system_dep(dep):
                continue
            candidate = resolve_dep(dep, conda_lib_dir, current)
            if not candidate:
                continue
            if not candidate.exists():
                continue
            resolved = candidate.resolve()
            if plugin_dir.exists() and is_under(resolved, plugin_dir):
                add_file(candidate, "gdalplugins")
            else:
                add_file(candidate, "lib")

    copy_files(lib_files, lib_out)
    create_symlinks(lib_symlinks, lib_out)

    if plugin_files or plugin_symlinks:
        copy_files(plugin_files, plugin_out)
        create_symlinks(plugin_symlinks, plugin_out)

    gdal_data = conda_prefix / "share" / "gdal"
    proj_data = conda_prefix / "share" / "proj"
    if gdal_data.exists():
        shutil.copytree(gdal_data, bundle_root / "share" / "gdal", dirs_exist_ok=True)
    if proj_data.exists():
        shutil.copytree(proj_data, bundle_root / "share" / "proj", dirs_exist_ok=True)
    java_dir = conda_prefix / "share" / "java"
    if java_dir.exists():
        java_out = bundle_root / "share" / "java"
        java_out.mkdir(parents=True, exist_ok=True)
        for jar in sorted(java_dir.glob("gdal*.jar")):
            if jar.name.endswith("-sources.jar") or jar.name.endswith("-javadoc.jar"):
                continue
            shutil.copy2(jar, java_out / jar.name)

    locations: Dict[str, str] = {}
    for name in lib_files:
        locations[name] = "lib"
    for name in lib_symlinks:
        locations[name] = "lib"
    for name in plugin_files:
        locations[name] = "gdalplugins"
    for name in plugin_symlinks:
        locations[name] = "gdalplugins"

    for name in lib_files:
        fix_install_names(lib_out / name, "lib", locations)
    for name in plugin_files:
        fix_install_names(plugin_out / name, "gdalplugins", locations)

    if codesign_identity:
        dylibs_to_sign = [lib_out / name for name in sorted(lib_files)]
        dylibs_to_sign.extend(plugin_out / name for name in sorted(plugin_files))
        codesign_files(dylibs_to_sign, codesign_identity)

    print("Bundled GDAL into:", bundle_root)
    print("Libs:", len(lib_files), "Symlinks:", len(lib_symlinks))
    print("Plugins:", len(plugin_files), "Symlinks:", len(plugin_symlinks))


if __name__ == "__main__":
    main()
