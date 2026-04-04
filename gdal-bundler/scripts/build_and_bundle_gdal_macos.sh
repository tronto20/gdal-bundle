#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: build_and_bundle_gdal_macos.sh [options]

Options:
  --conda-prefix <path>    Conda environment prefix (required)
  --conda-exe <path>       Conda executable path (defaults to CONDA_EXE or PATH)
  --work-dir <path>        Working directory (default: <module>/build/gdal-work)
  --output-dir <path>      Bundle output directory (default: <module>/build/gdal-bundle)
  --gdal-version <ver>     GDAL version (default: 3.12.2)
  --libkml-version <ver>   libkml version (default: 1.3.0)
  --codesign-identity <id> Codesign identity for bundled dylibs
  --install-deps           Install conda dependencies into the prefix (used with --step all)
  --clean                  Remove work dir before building
  --step <name>            deps | libkml | gdal | bundle | all (default: all)
  -h, --help               Show this help
USAGE
}

conda_prefix=""
conda_exe_path=""
work_dir=""
output_dir=""
gdal_version="3.12.2"
libkml_version="1.3.0"
codesign_identity=""
install_deps=0
clean=0
step="all"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --conda-prefix)
      conda_prefix="$2"
      shift 2
      ;;
    --conda-exe)
      conda_exe_path="$2"
      shift 2
      ;;
    --work-dir)
      work_dir="$2"
      shift 2
      ;;
    --output-dir)
      output_dir="$2"
      shift 2
      ;;
    --gdal-version)
      gdal_version="$2"
      shift 2
      ;;
    --libkml-version)
      libkml_version="$2"
      shift 2
      ;;
    --codesign-identity)
      codesign_identity="$2"
      shift 2
      ;;
    --install-deps)
      install_deps=1
      shift
      ;;
    --clean)
      clean=1
      shift
      ;;
    --step)
      step="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

case "$step" in
  all|deps|libkml|gdal|bundle)
    ;;
  *)
    echo "Unknown step: $step" >&2
    usage
    exit 1
    ;;
esac

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd "$script_dir/.." && pwd)"

if [[ -z "$work_dir" ]]; then
  work_dir="$project_dir/build/gdal-work"
fi
if [[ -z "$output_dir" ]]; then
  output_dir="$project_dir/build/gdal-bundle"
fi

if [[ -z "$conda_prefix" ]]; then
  echo "CONDA_PREFIX or --conda-prefix is required." >&2
  exit 1
fi

shell_os="$(uname -s | tr '[:upper:]' '[:lower:]')"
is_windows_shell=0
case "$shell_os" in
  mingw*|msys*|cygwin*)
    is_windows_shell=1
    ;;
esac

normalize_path() {
  local value="$1"
  if [[ -z "$value" ]]; then
    printf '%s' "$value"
    return
  fi
  if [[ "$is_windows_shell" == "1" ]] && command -v cygpath >/dev/null 2>&1; then
    cygpath -u "$value"
  else
    printf '%s' "$value"
  fi
}

conda_prefix="$(normalize_path "$conda_prefix")"
conda_exe_path="$(normalize_path "$conda_exe_path")"
work_dir="$(normalize_path "$work_dir")"
output_dir="$(normalize_path "$output_dir")"

if [[ ! -d "$conda_prefix" ]]; then
  echo "Conda prefix not found: $conda_prefix" >&2
  exit 1
fi

needs_conda_exe=0
if [[ "$install_deps" == "1" || "$step" == "deps" ]]; then
  needs_conda_exe=1
fi

if [[ "$needs_conda_exe" == "1" ]]; then
  if [[ -z "$conda_exe_path" ]]; then
    if [[ -n "${CONDA_EXE:-}" ]]; then
      conda_exe_path="${CONDA_EXE}"
    fi
  fi
  if [[ -z "$conda_exe_path" ]]; then
    conda_exe_path="$(command -v conda || true)"
  fi
  conda_exe_path="$(normalize_path "$conda_exe_path")"
  if [[ -z "$conda_exe_path" || ! -x "$conda_exe_path" ]]; then
    echo "Conda executable not found. Use --conda-exe or set CONDA_EXE." >&2
    exit 1
  fi
fi

gdal_tar="$work_dir/gdal-${gdal_version}.tar.gz"
gdal_src="$work_dir/gdal-${gdal_version}"
libkml_tar="$work_dir/libkml-${libkml_version}.tar.gz"
libkml_src="$work_dir/libkml-${libkml_version}"
libkml_build="$work_dir/libkml-build-${libkml_version}"
gdal_build="$work_dir/gdal-build-${gdal_version}"

cmake_bin=""
python_bin=""
jobs=""

ensure_work_dir() {
  if [[ "$clean" == "1" ]]; then
    rm -rf "$work_dir"
  fi
  mkdir -p "$work_dir"
}

resolve_cmake_bin() {
  if [[ -n "$cmake_bin" ]]; then
    return
  fi
  if [[ "$is_windows_shell" == "1" ]]; then
    for candidate in \
      "$conda_prefix/Library/bin/cmake.exe" \
      "$conda_prefix/Scripts/cmake.exe" \
      "$conda_prefix/bin/cmake.exe"; do
      if [[ -x "$candidate" ]]; then
        cmake_bin="$candidate"
        return
      fi
    done
  fi
  cmake_bin="$conda_prefix/bin/cmake"
  if [[ ! -x "$cmake_bin" ]]; then
    cmake_bin="$(command -v cmake || true)"
  fi
  if [[ -z "$cmake_bin" ]]; then
    echo "cmake not found. Install it in the conda environment." >&2
    exit 1
  fi
}

resolve_python_bin() {
  if [[ -n "$python_bin" ]]; then
    return
  fi
  python_bin="$(command -v python3 || true)"
  if [[ -z "$python_bin" ]]; then
    python_bin="$(command -v python || true)"
  fi
  if [[ -z "$python_bin" ]]; then
    if [[ "$is_windows_shell" == "1" ]]; then
      for candidate in \
        "$conda_prefix/Scripts/python.exe" \
        "$conda_prefix/Library/bin/python.exe" \
        "$conda_prefix/python.exe"; do
        if [[ -x "$candidate" ]]; then
          python_bin="$candidate"
          break
        fi
      done
    else
      if [[ -x "$conda_prefix/bin/python" ]]; then
        python_bin="$conda_prefix/bin/python"
      fi
    fi
  fi
  if [[ -z "$python_bin" ]]; then
    echo "python3 or python not found." >&2
    exit 1
  fi
}

retry() {
  local attempts="$1"
  shift
  local attempt=1
  local delay=5
  while true; do
    if "$@"; then
      return 0
    fi
    if [[ "$attempt" -ge "$attempts" ]]; then
      return 1
    fi
    echo "Command failed; retrying in ${delay}s (${attempt}/${attempts})..." >&2
    sleep "$delay"
    attempt=$((attempt + 1))
    delay=$((delay * 2))
  done
}

ensure_jobs() {
  if [[ -n "$jobs" ]]; then
    return
  fi
  if [[ "$(uname -s | tr '[:upper:]' '[:lower:]')" == linux* ]]; then
    jobs="$(nproc 2>/dev/null || echo 8)"
  else
    jobs="$(sysctl -n hw.ncpu 2>/dev/null || echo 8)"
  fi
}

setup_build_env() {
  export CONDA_PREFIX="$conda_prefix"
  if [[ "$is_windows_shell" == "1" ]]; then
    export PATH="$conda_prefix/Library/bin:$conda_prefix/Library/mingw-w64/bin:$conda_prefix/Scripts:$conda_prefix/Library/usr/bin:$conda_prefix:$PATH"
    export PKG_CONFIG_LIBDIR="$conda_prefix/Library/lib/pkgconfig:$conda_prefix/Library/share/pkgconfig"
  else
    export PATH="$conda_prefix/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
    export PKG_CONFIG_LIBDIR="$conda_prefix/lib/pkgconfig:$conda_prefix/share/pkgconfig"
  fi
  export PKG_CONFIG_PATH="$PKG_CONFIG_LIBDIR"
  if [[ -d "$conda_prefix/lib/jvm" ]]; then
    export JAVA_HOME="$conda_prefix/lib/jvm"
  elif [[ -d "$conda_prefix/Library/lib/jvm" ]]; then
    export JAVA_HOME="$conda_prefix/Library/lib/jvm"
  fi
  if [[ -z "${CC:-}" ]]; then
    CC="$(command -v cc || true)"
    export CC
  fi
  if [[ -z "${CXX:-}" ]]; then
    CXX="$(command -v c++ || true)"
    export CXX
  fi
  if [[ -z "${CC:-}" || -z "${CXX:-}" ]]; then
    echo "C/C++ compiler not found. Install a native toolchain (for example build-essential on Linux or Xcode CLT on macOS)." >&2
    exit 1
  fi
}

download_libkml() {
  if [[ ! -f "$libkml_tar" ]]; then
    curl -fL --retry 5 --retry-all-errors --connect-timeout 20 --retry-delay 5 \
      -o "$libkml_tar" "https://codeload.github.com/libkml/libkml/tar.gz/refs/tags/${libkml_version}"
  fi
  if [[ ! -d "$libkml_src" ]]; then
    tar -xzf "$libkml_tar" -C "$work_dir"
  fi
}

download_gdal() {
  if [[ ! -f "$gdal_tar" ]]; then
    curl -fL --retry 5 --retry-all-errors --connect-timeout 20 --retry-delay 5 \
      -o "$gdal_tar" "https://github.com/OSGeo/gdal/releases/download/v${gdal_version}/gdal-${gdal_version}.tar.gz"
  fi
  if [[ ! -d "$gdal_src" ]]; then
    tar -xzf "$gdal_tar" -C "$work_dir"
  fi
}

patch_libkml_minizip() {
  local minizip_cmake="$libkml_src/cmake/External_minizip.cmake"
  if [[ -f "$minizip_cmake" ]] && ! grep -q "CMAKE_POLICY_VERSION_MINIMUM" "$minizip_cmake"; then
    MINIZIP_CMAKE="$minizip_cmake" "$python_bin" - <<'PY'
from pathlib import Path
import os

path = Path(os.environ["MINIZIP_CMAKE"])
text = path.read_text()
needle = "-DCMAKE_BUILD_TYPE:STRING=${CMAKE_BUILD_TYPE}\n"
insert = needle + "  -DCMAKE_POLICY_VERSION_MINIMUM:STRING=3.5\n"
if "CMAKE_POLICY_VERSION_MINIMUM" not in text and needle in text:
    path.write_text(text.replace(needle, insert))
PY
  fi
}

install_conda_deps() {
  local packages=(
    cmake ninja pkg-config swig ant openjdk
    geos proj sqlite libxml2 xerces-c curl openssl
    libtiff libjpeg-turbo libpng giflib zlib zstd lz4-c snappy bzip2 xz libdeflate
    expat json-c muparser libspatialite freexl pcre2 libiconv
    hdf5 libnetcdf cfitsio openjpeg openexr imath libheif libaec geotiff
    qhull libarchive libwebp libjxl aws-sdk-cpp minizip uriparser boost-cpp
  )
  if [[ "$is_windows_shell" != "1" ]]; then
    packages+=(unixodbc)
  fi
  retry 3 env CONDA_NO_PLUGINS=true "$conda_exe_path" install -y -p "$conda_prefix" -c conda-forge --solver=classic "${packages[@]}"
}

cmake_prefix_path() {
  if [[ "$is_windows_shell" == "1" ]]; then
    printf '%s;%s' "$conda_prefix/Library" "$conda_prefix"
  else
    printf '%s' "$conda_prefix"
  fi
}

resolve_minizip_paths() {
  if [[ "$is_windows_shell" != "1" ]]; then
    return 1
  fi

  local include_dir=""
  for candidate in \
    "$conda_prefix/Library/include" \
    "$conda_prefix/include"; do
    if [[ -d "$candidate/minizip" ]] && { [[ -f "$candidate/minizip/unzip.h" ]] || [[ -f "$candidate/minizip/zip.h" ]]; }; then
      include_dir="$candidate"
      break
    fi
  done

  local library_path=""
  for candidate in \
    "$conda_prefix/Library/lib/minizip.lib" \
    "$conda_prefix/Library/lib/libminizip.lib" \
    "$conda_prefix/Library/lib/minizip.dll.a" \
    "$conda_prefix/Library/lib/libminizip.dll.a" \
    "$conda_prefix/Library/lib/minizip.a" \
    "$conda_prefix/Library/lib/libminizip.a" \
    "$conda_prefix/Library/lib/minizip" \
    "$conda_prefix/Library/lib/libminizip"; do
    if [[ -f "$candidate" ]]; then
      library_path="$candidate"
      break
    fi
  done
  if [[ -z "$library_path" ]] && [[ -d "$conda_prefix/Library/lib" ]]; then
    library_path="$(find "$conda_prefix/Library/lib" -maxdepth 1 -type f \( -iname 'minizip*' -o -iname 'libminizip*' \) | head -n 1)"
  fi

  if [[ -z "$include_dir" || -z "$library_path" ]]; then
    echo "Minizip package not found in $conda_prefix/Library." >&2
    return 1
  fi

  MINIZIP_INCLUDE_DIR="$include_dir"
  MINIZIP_LIBRARY="$library_path"
}

build_libkml() {
  resolve_cmake_bin
  resolve_python_bin
  ensure_jobs
  download_libkml
  patch_libkml_minizip
  setup_build_env

  local minizip_args=(
    -DMINIZIP_INCLUDE_DIR=MINIZIP_INCLUDE_DIR-NOTFOUND
    -DMINIZIP_LIBRARY=MINIZIP_LIBRARY-NOTFOUND
    -DMINIZIP_FOUND=FALSE
  )
  if [[ "$is_windows_shell" == "1" ]]; then
    resolve_minizip_paths
    minizip_args=(
      -DMINIZIP_INCLUDE_DIR="$MINIZIP_INCLUDE_DIR"
      -DMINIZIP_LIBRARY="$MINIZIP_LIBRARY"
    )
  fi

  "$cmake_bin" -S "$libkml_src" -B "$libkml_build" -GNinja \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$conda_prefix" \
    -DCMAKE_PREFIX_PATH="$(cmake_prefix_path)" \
    -DCMAKE_IGNORE_PATH="/opt/homebrew;/usr/local;/opt/local" \
    -DCMAKE_IGNORE_PREFIX_PATH="/opt/homebrew;/usr/local;/opt/local" \
    -DCMAKE_FIND_FRAMEWORK=LAST \
    -DBUILD_SHARED_LIBS=ON \
    -DBUILD_TESTING=OFF \
    -DBUILD_EXAMPLES=OFF \
    -DINSTALL_EXAMPLES=OFF \
    -DWITH_SWIG=OFF \
    -DWITH_PYTHON=OFF \
    -DWITH_JAVA=OFF \
    "${minizip_args[@]}"

  "$cmake_bin" --build "$libkml_build" --parallel "$jobs"
  "$cmake_bin" --install "$libkml_build"
}

build_gdal() {
  resolve_cmake_bin
  ensure_jobs
  download_gdal
  setup_build_env

  "$cmake_bin" -S "$gdal_src" -B "$gdal_build" -GNinja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$conda_prefix" \
    -DCMAKE_PREFIX_PATH="$(cmake_prefix_path)" \
    -DCMAKE_IGNORE_PATH="/opt/homebrew;/usr/local;/opt/local" \
    -DCMAKE_IGNORE_PREFIX_PATH="/opt/homebrew;/usr/local;/opt/local" \
    -DCMAKE_FIND_FRAMEWORK=LAST \
    -DBUILD_PYTHON_BINDINGS=OFF \
    -DBUILD_JAVA_BINDINGS=ON \
    -DGDAL_JAVA_GENERATE_JAVADOC=OFF \
    -DBUILD_TESTING=OFF \
    -DGDAL_USE_ARROW=OFF \
    -DGDAL_USE_PARQUET=OFF \
    -DGDAL_USE_SFCGAL=OFF \
    -DGDAL_USE_POPPLER=OFF \
    -DGDAL_USE_LIBKML=ON

  "$cmake_bin" --build "$gdal_build" --parallel "$jobs"
  "$cmake_bin" --install "$gdal_build"
}

bundle_gdal() {
  resolve_python_bin
  local bundle_args=(
    "$project_dir/scripts/bundle_gdal.py"
    --conda-prefix "$conda_prefix"
    --output-dir "$output_dir"
  )
  if [[ -n "$codesign_identity" ]]; then
    bundle_args+=(--codesign-identity "$codesign_identity")
  fi
  "$python_bin" "${bundle_args[@]}"
  echo "Bundle ready: $output_dir/gdal"
}

case "$step" in
  deps)
    install_conda_deps
    ;;
  libkml)
    ensure_work_dir
    build_libkml
    ;;
  gdal)
    ensure_work_dir
    build_gdal
    ;;
  bundle)
    bundle_gdal
    ;;
  all)
    ensure_work_dir
    if [[ "$install_deps" == "1" ]]; then
      install_conda_deps
    fi
    build_libkml
    build_gdal
    bundle_gdal
    ;;
esac
