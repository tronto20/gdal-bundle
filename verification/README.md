# GDAL multiplatform feasibility probe

This standalone build tests whether Kotlin can actually invoke GDAL on each
runtime before expanding the production API. It deliberately does not publish
packages or change the production modules. WASI is excluded by request.

## What a pass means

The Kotlin entry point initiates the GDAL call and checks its result. Every
execution must exit successfully and print `GDAL4K_PROBE_OK`.

The shared 3 x 2 Byte GeoTIFF has pixels `[0, 1, 2, 3, 4, 255]`, NoData `0`,
EPSG:4326, and geotransform `[100, 0.5, 0, 50, 0, -0.5]`. Probes check:

- dimensions, band count, exact pixels, NoData, and geotransform;
- GeoTIFF creation, reopening, and exact output pixels;
- COG creation, reopening, COG layout metadata, and exact output pixels;
- EPSG:4326 to EPSG:3857 transformation of `(10, 45)` within 1 cm;
- a GeoJSON layer containing one feature;
- dataset and transformation cleanup.

The C/JNI/Native and web probes use the same TIFF fixture. C embeds those bytes
in `native/sample_tif.h`; the web probe reads `fixtures/sample.tif`.
This is a small-input feasibility check, not a performance benchmark, full API
compatibility test, production package test, or guarantee of identical drivers.

## Execution paths

| Consumer | GDAL execution |
| --- | --- |
| JVM / Android ART | Kotlin -> JNI C probe -> platform GDAL |
| Kotlin/Native | Kotlin -> C interop -> C probe -> platform GDAL |
| Kotlin/JS, Node and browser | Kotlin -> JS adapter -> GDAL WebAssembly |
| Kotlin/wasmJs, Node and browser | Kotlin Wasm -> JS interop -> GDAL WebAssembly |

The web spike pins `gdal3.js@2.8.1` as an existing real engine to validate the
interop path. It is not the final binding or build recipe. The host probes use
installed GDAL versions, so version/driver parity remains separate work.
The standalone compiler is Kotlin 2.4.21; production remains on its existing
compiler and JVM-only configuration.

## Host probes

Install JDK 21, Python 3.14, Node 24, a C/C++ compiler, and GDAL development files.
On macOS: `brew install gdal ninja`. On Ubuntu:
`sudo apt-get install libgdal-dev ninja-build`. On Windows use MinGW GDAL,
GCC, CMake and Ninja, and put that prefix on `PATH` and `CMAKE_PREFIX_PATH`.
Set `JAVA_HOME` to the JDK when CMake cannot discover JNI.

```sh
cd verification
python3 -m venv tools/python
tools/python/bin/python -m pip install -r requirements.txt
npm ci
npx playwright install --with-deps chromium
PATH="$PWD/tools/python/bin:$PATH" tools/python/bin/python run-local.py
```

On Windows, use `python` and `gradlew.bat` through the runner; its default
Gradle entry point is selected automatically. `run-local.py` writes raw logs
and a timestamped `results/local.json`. No missing runtime is counted as a pass.

For cross-compilation set `-PprobePrefix=/absolute/path/to/built-C-library`,
then run the resulting executable on the matching OS/architecture. The Native
targets in this first probe are macOS ARM64, Linux x64/ARM64, Windows x64, and
iOS Simulator ARM64. The iOS CI job builds its own GDAL and runs a Native test
on an actual booted Simulator. Apple physical-device targets and other ABIs
still need their own builds and execution evidence.

## Android

The Android spike uses NDK r28c, vcpkg commit
`3cd21e48a75f67acfef289da52893ce79144aaa1`, and `gdal[core]:arm64-android`.
Build the CMake probe with vcpkg's toolchain chain-loading the NDK toolchain,
`ANDROID_ABI=arm64-v8a`, `ANDROID_PLATFORM=android-28`, and `Release`.
Stage the Kotlin JVM JAR and its runtime with `stageJvmRuntime`, convert them
with Android `d8`, and run the resulting DEX on Android ART with `app_process`.
Provide the probe shared library, `libc++_shared.so`, and PROJ data in the
isolated test directory. This checks Kotlin bytecode and JNI on Android; AAR/APK
packaging, scoped-storage integration, real devices and other ABIs are separate.

With the SDK, NDK and vcpkg GDAL build prepared and a matching emulator running:

```sh
python run-android.py --sdk /path/to/sdk --ndk /path/to/ndk \
  --vcpkg /path/to/vcpkg --serial emulator-5580 --abi arm64-v8a
```

The runner verifies the device ABI, builds the bridge, compiles the actual Kotlin
JARs to DEX, copies native/runtime data into its own `/data/local/tmp` directory,
and records Android version, ABI, page size, GDAL output and execution status.
The initial local run passed on Android 15 ARM64 with 4 KB pages and GDAL 3.12.4;
16 KB page-size execution remains unverified.

## CI

`.github/workflows/platform-feasibility.yml` is a manual (`workflow_dispatch`) diagnostic workflow. It runs host probes on Linux x64,
macOS ARM64, Windows x64, and iOS Simulator ARM64 and preserves the actual logs. It has read-only
repository permissions and no package publication steps.

Pull requests and `main` are validated by the production library test source sets in `.github/workflows/library-tests.yml`.
