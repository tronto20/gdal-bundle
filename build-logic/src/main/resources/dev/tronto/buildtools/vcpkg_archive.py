#!/usr/bin/env python3
"""Private adapters for the pinned vcpkg archive calls; not general zip/tar replacements.

POSIX only. Windows vcpkg uses its own acquired 7-Zip and the OS tar.exe.
Unknown flags fail rather than falling back to an arbitrary system executable.
"""
import fnmatch
import glob
import os
from pathlib import Path, PurePosixPath
import stat
import subprocess
import sys
import zipfile


def safe_member(root, name):
    parts = PurePosixPath(name.replace('\\', '/'))
    if parts.is_absolute() or '..' in parts.parts or any(':' in p for p in parts.parts):
        raise ValueError('Unsafe zip member: ' + name)
    result = root.joinpath(*parts.parts)
    if not result.resolve().is_relative_to(root.resolve()):
        raise ValueError('Zip member escapes destination: ' + name)
    return result


def unzip(args):
    # vcpkg extraction: unzip -qqo archive; binary cache: unzip -DD -qq archive -d dst.
    while args and args[0] in ('-qqo', '-DD', '-qq'):
        args = args[1:]
    if len(args) == 1:
        archive, destination = args[0], Path.cwd()
    elif len(args) == 2 and args[1].startswith('-d') and len(args[1]) > 2:
        archive, destination = args[0], Path(args[1][2:])
    elif len(args) == 3 and args[1] == '-d':
        archive, destination = args[0], Path(args[2])
    else:
        raise ValueError('Unsupported vcpkg unzip arguments: ' + repr(args))
    destination.mkdir(parents=True, exist_ok=True)
    links = []
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            out = safe_member(destination, entry.filename)
            mode = entry.external_attr >> 16
            if entry.is_dir():
                out.mkdir(parents=True, exist_ok=True)
            elif stat.S_ISLNK(mode):
                links.append((out, source.read(entry).decode('utf-8')))
            else:
                out.parent.mkdir(parents=True, exist_ok=True)
                # Never follow an old extraction's final symlink, even if it is in-root.
                if out.is_symlink():
                    out.unlink()
                with source.open(entry) as src, out.open('wb') as dst:
                    import shutil
                    shutil.copyfileobj(src, dst)
                out.chmod((mode & 0o777) or 0o644)
    for out, target in links:
        if '\\' in target or ':' in target or Path(target).is_absolute() or not (out.parent / target).resolve().is_relative_to(destination.resolve()):
            raise ValueError('Unsafe zip symlink: ' + target)
        out.parent.mkdir(parents=True, exist_ok=True)
        if out.is_symlink() or out.is_file():
            out.unlink()
        out.symlink_to(target)


def compress(args):
    level = 6
    if args and len(args[0]) == 2 and args[0][0] == '-' and args[0][1].isdigit():
        level = int(args.pop(0)[1])
    if args[:3] != ['--quiet', '-y', '-r'] or '--exclude' not in args:
        raise ValueError('Unsupported vcpkg zip arguments: ' + repr(args))
    split = args.index('--exclude')
    if split < 5 or args[split + 1:] != ['.DS_Store']:
        raise ValueError('Unsupported vcpkg zip exclusions')
    destination = Path(args[3]).resolve()
    roots = []
    for name in args[4:split]:
        roots.extend(glob.glob(name))
    if not roots:
        raise ValueError('No vcpkg package files to archive')
    members = set()
    for name in roots:
        path = Path(name)
        if path.is_absolute() or '..' in path.parts:
            raise ValueError('Only package-relative zip inputs are supported')
        members.add(path)
        if path.is_dir() and not path.is_symlink():
            for directory, dirs, files in os.walk(path, followlinks=False):
                members.update(Path(directory) / item for item in dirs + files)
    with zipfile.ZipFile(destination, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=level, strict_timestamps=False) as output:
        for member in sorted(members):
            if fnmatch.fnmatch(member.name, '.DS_Store') or member.resolve() == destination:
                continue
            if member.is_symlink():
                info = zipfile.ZipInfo(member.as_posix())
                info.create_system = 3
                info.external_attr = (stat.S_IFLNK | 0o777) << 16
                output.writestr(info, os.readlink(member))
            else:
                output.write(member, member.as_posix())


def main():
    tool, cmake, *args = sys.argv[1:]
    if tool == 'tar':
        if len(args) != 2 or args[0] != 'xzf':
            raise ValueError('Unsupported vcpkg tar arguments: ' + repr(args))
        subprocess.run([cmake, '-E', 'tar', 'xf', args[1]], check=True)
    elif tool == 'zip':
        compress(args)
    elif tool == 'unzip':
        unzip(args)
    else:
        raise ValueError('Unknown vcpkg archive adapter: ' + tool)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
