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
  if [[ -z "$python_bin" && -x "$conda_prefix/bin/python" ]]; then
    python_bin="$conda_prefix/bin/python"
  fi
  if [[ -z "$python_bin" ]]; then
    echo "python3 not found." >&2
    exit 1
  fi
}

ensure_jobs() {
  if [[ -n "$jobs" ]]; then
    return
  fi
  jobs="$(sysctl -n hw.ncpu 2>/dev/null || echo 8)"
}

setup_build_env() {
  export CONDA_PREFIX="$conda_prefix"
  export PATH="$conda_prefix/bin:/usr/bin:/bin:/usr/sbin:/sbin"
  export PKG_CONFIG_LIBDIR="$conda_prefix/lib/pkgconfig:$conda_prefix/share/pkgconfig"
  export PKG_CONFIG_PATH="$PKG_CONFIG_LIBDIR"
  if [[ -d "$conda_prefix/lib/jvm" ]]; then
    export JAVA_HOME="$conda_prefix/lib/jvm"
  fi
}

download_libkml() {
  if [[ ! -f "$libkml_tar" ]]; then
    curl -L -o "$libkml_tar" "https://codeload.github.com/libkml/libkml/tar.gz/refs/tags/${libkml_version}"
  fi
  if [[ ! -d "$libkml_src" ]]; then
    tar -xzf "$libkml_tar" -C "$work_dir"
  fi
}

download_gdal() {
  if [[ ! -f "$gdal_tar" ]]; then
    curl -L -o "$gdal_tar" "https://github.com/OSGeo/gdal/releases/download/v${gdal_version}/gdal-${gdal_version}.tar.gz"
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
  CONDA_NO_PLUGINS=true "$conda_exe_path" install -y -p "$conda_prefix" -c conda-forge --solver=classic \
    cmake ninja pkg-config swig ant openjdk \
    geos proj sqlite libxml2 xerces-c curl openssl \
    libtiff libjpeg-turbo libpng giflib zlib zstd lz4-c snappy bzip2 xz libdeflate \
    expat json-c muparser libspatialite freexl pcre2 libiconv \
    hdf5 libnetcdf cfitsio openjpeg openexr imath libheif libaec geotiff \
    qhull libarchive libwebp libjxl unixodbc aws-sdk-cpp minizip uriparser boost-cpp
}

build_libkml() {
  resolve_cmake_bin
  resolve_python_bin
  ensure_jobs
  download_libkml
  patch_libkml_minizip
  setup_build_env

  "$cmake_bin" -S "$libkml_src" -B "$libkml_build" -GNinja \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$conda_prefix" \
    -DCMAKE_PREFIX_PATH="$conda_prefix" \
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
    -DMINIZIP_INCLUDE_DIR=MINIZIP_INCLUDE_DIR-NOTFOUND \
    -DMINIZIP_LIBRARY=MINIZIP_LIBRARY-NOTFOUND \
    -DMINIZIP_FOUND=FALSE

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
    -DCMAKE_PREFIX_PATH="$conda_prefix" \
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
    "$project_dir/scripts/bundle_gdal_macos.py"
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
