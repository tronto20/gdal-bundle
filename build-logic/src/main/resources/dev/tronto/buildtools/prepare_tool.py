#!/usr/bin/env python3
"""Private, task-time installer. Uses existing Python 3.9+ and Git; never installs them."""
import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.parse
import urllib.request
import zipfile

SCHEMA = 1
RECEIPT = '.build-tool.json'


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


@contextmanager
def exclusive_lock(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a+b') as lock:
        if os.name == 'nt':
            import msvcrt
            lock.seek(0)
            lock.write(b'0')
            lock.flush()
            lock.seek(0)
            while True:
                try:
                    msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
                    break
                except OSError:
                    time.sleep(.2)
        else:
            import fcntl
            fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            yield
        finally:
            if os.name == 'nt':
                lock.seek(0)
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(lock, fcntl.LOCK_UN)


def safe_path(root, name):
    # Treat Windows separators and drive names as unsafe even on POSIX.
    pure = PurePosixPath(name.replace('\\', '/'))
    if pure.is_absolute() or '..' in pure.parts or any(':' in p for p in pure.parts):
        raise ValueError('Unsafe archive path: ' + name)
    target = root.joinpath(*pure.parts)
    if not target.resolve().is_relative_to(root.resolve()):
        raise ValueError('Archive path escapes extraction directory: ' + name)
    return target


def extract(archive, destination, zipped):
    # Do not follow links while extracting. Safe relative symlinks are created last.
    links = []
    if zipped:
        with zipfile.ZipFile(archive) as source:
            for entry in source.infolist():
                out = safe_path(destination, entry.filename)
                mode = entry.external_attr >> 16
                if entry.is_dir():
                    out.mkdir(parents=True, exist_ok=True)
                elif stat.S_ISLNK(mode):
                    links.append((out, source.read(entry).decode('utf-8')))
                else:
                    out.parent.mkdir(parents=True, exist_ok=True)
                    with source.open(entry) as src, out.open('wb') as dst:
                        shutil.copyfileobj(src, dst)
                    out.chmod((mode & 0o777) or 0o644)
    else:
        with tarfile.open(archive, 'r:gz') as source:
            for entry in source:
                out = safe_path(destination, entry.name)
                if entry.isdir():
                    out.mkdir(parents=True, exist_ok=True)
                elif entry.issym():
                    links.append((out, entry.linkname))
                elif entry.isfile():
                    out.parent.mkdir(parents=True, exist_ok=True)
                    with source.extractfile(entry) as src, out.open('wb') as dst:
                        shutil.copyfileobj(src, dst)
                    out.chmod(entry.mode & 0o777)
                else:
                    raise ValueError('Unsupported archive entry: ' + entry.name)
    for out, link in links:
        if Path(link).is_absolute() or '\\' in link or ':' in link:
            raise ValueError('Unsafe archive symlink: ' + link)
        if not (out.parent / link).resolve().is_relative_to(destination.resolve()):
            raise ValueError('Archive symlink escapes extraction directory: ' + link)
        if out.exists() or out.is_symlink():
            raise ValueError('Duplicate archive symlink: ' + str(out))
        out.parent.mkdir(parents=True, exist_ok=True)
        out.symlink_to(link)


def download(url, checksum, store, offline):
    if not re.fullmatch(r'[0-9a-fA-F]{64}', checksum):
        raise ValueError('A pinned SHA-256 is required for this tool version/host; configure sha256 explicitly')
    # The same release can be used by two host/installation directories.
    with exclusive_lock(store / 'downloads' / (checksum + '.lock')):
        return _download(url, checksum, store, offline)


def _download(url, checksum, store, offline):
    if not re.fullmatch(r'[0-9a-fA-F]{64}', checksum):
        raise ValueError('A pinned SHA-256 is required for this tool version/host; configure sha256 explicitly')
    checksum = checksum.lower()
    archive = store / 'downloads' / checksum
    if archive.is_file() and digest(archive) == checksum:
        return archive
    if offline:
        raise RuntimeError('Offline: verified download is not cached: ' + str(archive))
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in ('https', 'file'):
        raise ValueError('Tool downloads require HTTPS (or a local file: fixture/mirror)')
    archive.parent.mkdir(parents=True, exist_ok=True)
    partial = archive.with_suffix('.partial')
    try:
        request = urllib.request.Request(url, headers={'User-Agent': 'Gradle-build-tools'})
        with urllib.request.urlopen(request, timeout=60) as src, partial.open('wb') as dst:
            shutil.copyfileobj(src, dst)
        if digest(partial) != checksum:
            raise ValueError('SHA-256 mismatch for ' + url)
        partial.replace(archive)
    finally:
        partial.unlink(missing_ok=True)
    return archive


def run(*command, cwd=None, capture=False, env=None):
    result = subprocess.run([str(x) for x in command], cwd=cwd, env=env, check=True,
                            text=True, encoding='utf-8', stdout=subprocess.PIPE if capture else None)
    return result.stdout.strip() if capture else None


def git_checkout(config, destination):
    revision = config['revision']
    if not re.fullmatch('[0-9a-f]{40}', revision):
        raise ValueError('A full pinned Git revision is required; configure revision explicitly')
    run(config['git'], 'init', destination)
    run(config['git'], '-C', destination, 'fetch', '--depth', '1', config['url'], revision)
    run(config['git'], '-C', destination, 'checkout', '--detach', 'FETCH_HEAD')
    if run(config['git'], '-C', destination, 'rev-parse', 'HEAD', capture=True) != revision:
        raise ValueError('Pinned Git revision mismatch')


def identity(config):
    result = {k: config[k] for k in ('tool', 'version', 'host', 'url', 'sha256', 'revision', 'executable')}
    if config['tool'] == 'vcpkg':
        result.update({k: config.get(k, '') for k in ('binary_version', 'binary_url', 'binary_sha256')})
    return result


def valid_install(config, destination):
    try:
        receipt = json.loads((destination / RECEIPT).read_text(encoding='utf-8'))
        expected = identity(config)
        previous = receipt.get('identity', {})
        if receipt.get('schema') != SCHEMA:
            return False
        if previous != expected:
            # Adopt no state and rewrite nothing: an earlier managed bootstrap installation
            # is reusable only when its original identity AND the newly pinned binary match.
            legacy = {k: v for k, v in expected.items() if not k.startswith('binary_')}
            if config['tool'] != 'vcpkg' or previous != legacy:
                return False
        if config['tool'] == 'vcpkg' and digest(destination / config['executable']) != config.get('binary_sha256', '').lower():
            return False
        if not receipt.get('files'):
            return False
        for name, checksum in receipt['files'].items():
            if digest(safe_path(destination, name)) != checksum:
                return False
        if config['tool'] in ('vcpkg', 'emscripten'):
            if run(config['git'], '-C', destination, 'rev-parse', 'HEAD', capture=True) != config['revision']:
                return False
            # Changes to the pinned checkout must not be silently accepted.
            if run(config['git'], '--no-optional-locks', '-C', destination, 'status', '--porcelain', '--untracked-files=no', capture=True):
                return False
        return True
    except (OSError, ValueError, subprocess.CalledProcessError):
        return False


def receipt_files(config, destination):
    files = [config['executable']]
    if config['tool'] == 'emscripten':
        files += ['.emscripten', 'upstream/bin/clang' + ('.exe' if config['host'].startswith('windows') else ''),
                  'upstream/bin/wasm-opt' + ('.exe' if config['host'].startswith('windows') else '')]
    return {name: digest(destination / name) for name in files}


def require_version(config, output):
    if config['tool'] == 'cmake':
        match = re.search(r'^cmake version (\S+)', output, re.MULTILINE)
        version = match.group(1) if match else ''
    else:
        version = output.splitlines()[0].strip() if output else ''
    if version != config['version']:
        raise ValueError('Tool version mismatch: expected ' + config['version'] + ', found ' + output)


def validate_local(config, destination):
    executable = destination / config['executable']
    if not executable.is_file():
        raise RuntimeError('Existing tool executable not found: ' + str(executable))
    if config['tool'] in ('cmake', 'ninja'):
        output = run(executable, '--version', capture=True)
        require_version(config, output)
    else:
        if not re.fullmatch('[0-9a-f]{40}', config['revision']):
            raise ValueError('A full pinned Git revision is required for an existing checkout')
        if run(config['git'], '-C', destination, 'rev-parse', 'HEAD', capture=True) != config['revision']:
            raise ValueError('Existing tool Git revision mismatch')
        if run(config['git'], '--no-optional-locks', '-C', destination, 'status', '--porcelain', '--untracked-files=no', capture=True):
            raise ValueError('Existing tool has modified tracked source files')
        if config['tool'] == 'vcpkg':
            metadata = dict(line.split('=', 1) for line in (destination / 'scripts/vcpkg-tool-metadata.txt').read_text().splitlines() if '=' in line)
            release = metadata.get('VCPKG_TOOL_RELEASE_TAG', '')
            output = run(executable, 'version', capture=True)
            if not release or release not in output:
                raise ValueError('Existing vcpkg executable does not match its pinned source release: ' + output)
        if config['tool'] == 'emscripten':
            actual = (destination / 'upstream/emscripten/emscripten-version.txt').read_text().strip().strip('"')
            if actual.removesuffix('-git') != config['version'] or not (destination / '.emscripten').is_file():
                raise ValueError('Existing Emscripten version/configuration mismatch')
    print('Using existing ' + config['tool'] + ': ' + str(destination), flush=True)


def install(config):
    if config['host'] not in ('macos-x64', 'macos-arm64', 'linux-x64', 'linux-arm64', 'windows-x64', 'windows-arm64'):
        raise ValueError('Unsupported tool host: ' + config['host'])
    if config['tool'] == 'emscripten' and config['host'] == 'windows-arm64':
        raise RuntimeError('The pinned Emscripten SDK has no native Windows ARM64 payload; use a supported host (no automatic x64 fallback)')
    store = Path(config['directory']).resolve()
    destination = Path(config['installation']).resolve()
    if config.get('local'):
        validate_local(config, destination)
        return
    # Serialize by final destination even when separate builds use different stores.
    lock = destination.parent / ('.' + destination.name + '.lock')
    with exclusive_lock(lock):
        if valid_install(config, destination):
            print('Verified ' + config['tool'] + ': ' + str(destination), flush=True)
            return
        # An existing unrecognized or damaged installation is user-visible state.
        # Never erase it or accept a partial install as success.
        if destination.exists():
            raise RuntimeError('Unverified tool directory; choose another installationDirectory or inspect it: ' + str(destination))
        tool = config['tool']
        if tool in ('vcpkg', 'emscripten') and config['offline']:
            raise RuntimeError('Offline: a complete verified installation is required: ' + str(destination))
        destination.parent.mkdir(parents=True, exist_ok=True)
        stage = Path(tempfile.mkdtemp(prefix='.' + destination.name + '-install-', dir=destination.parent))
        try:
            if tool in ('cmake', 'ninja'):
                archive = download(config['url'], config['sha256'], store, config['offline'])
                extracted = stage / 'extracted'
                extracted.mkdir()
                extract(archive, extracted, tool == 'ninja' or config['host'].startswith('windows'))
                if tool == 'cmake':
                    entries = list(extracted.iterdir())
                    if len(entries) != 1 or not entries[0].is_dir():
                        raise ValueError('Expected one CMake archive root')
                    payload = entries[0]
                else:
                    payload = extracted
                for child in list(payload.iterdir()):
                    shutil.move(str(child), stage / child.name)
                shutil.rmtree(extracted)
                (stage / config['executable']).chmod(0o755)
                output = run(stage / config['executable'], '--version', capture=True)
                require_version(config, output)
            else:
                if tool == 'vcpkg':
                    # Validate and download BEFORE cloning. No bootstrap script, shell,
                    # curl, tar, zip or unzip is needed on any supported host.
                    binary = download(config.get('binary_url', ''), config.get('binary_sha256', ''), store, False)
                git_checkout(config, stage)
                if tool == 'vcpkg':
                    metadata = dict(line.split('=', 1) for line in (stage / 'scripts/vcpkg-tool-metadata.txt').read_text().splitlines() if '=' in line)
                    release = metadata.get('VCPKG_TOOL_RELEASE_TAG', '')
                    if not release or release != config.get('binary_version'):
                        raise ValueError('vcpkg source and executable release mismatch')
                    executable = stage / config['executable']
                    shutil.copy2(binary, executable)
                    executable.chmod(0o755)
                    output = run(executable, 'version', capture=True)
                    if release not in output:
                        raise ValueError('vcpkg executable version mismatch: ' + output)
                else:
                    run(sys.executable, stage / 'emsdk.py', 'install', config['version'], cwd=stage)
                    run(sys.executable, stage / 'emsdk.py', 'activate', config['version'], cwd=stage)
            hashes = receipt_files(config, stage)
            (stage / RECEIPT).write_text(json.dumps({'schema': SCHEMA, 'identity': identity(config), 'files': hashes}, indent=2) + '\n', encoding='utf-8')
            stage.rename(destination)
            # Embedded emsdk config uses EM_CONFIG to resolve paths after relocation.
            print('Prepared ' + tool + ': ' + str(destination), flush=True)
        finally:
            if stage.exists():
                # Only this invocation's fresh staging directory is owned here.
                def writable_remove(function, path, exc):
                    os.chmod(path, stat.S_IWRITE)
                    function(path)
                shutil.rmtree(stage, onerror=writable_remove)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ('tool', 'version', 'host', 'directory', 'installation', 'url', 'sha256', 'revision', 'executable', 'git'):
        parser.add_argument('--' + key, required=True)
    for key in ('binary-version', 'binary-url', 'binary-sha256'):
        parser.add_argument('--' + key, default='')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--local', action='store_true')
    config = vars(parser.parse_args())
    if config['tool'] not in ('cmake', 'ninja', 'emscripten', 'vcpkg'):
        parser.error('Unknown tool')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', config['version']):
        parser.error('Version must be a directory-safe version or revision')
    safe_path(Path(config['installation']), config['executable'])
    try:
        install(config)
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        parser.exit(1, str(error) + '\n')


if __name__ == '__main__':
    main()
