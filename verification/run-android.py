#!/usr/bin/env python3
"""Execute the same Kotlin/JNI GDAL probe on a running Android ART runtime."""
import argparse
import datetime
import json
import os
from pathlib import Path
import platform
import subprocess
import sys

ROOT = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--vcpkg", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--abi", choices=["arm64-v8a", "x86_64"], default="arm64-v8a")
    parser.add_argument("--gdal-dir", type=Path, help="Optional raw GDAL CMake package, before vcpkg fixup")
    parser.add_argument("--cmake", default="cmake")
    parser.add_argument("--gradle", default=str(ROOT / ("gradlew.bat" if platform.system() == "Windows" else "gradlew")))
    args = parser.parse_args()
    for name in ("sdk", "ndk", "vcpkg"):
        setattr(args, name, getattr(args, name).resolve())
    if args.gdal_dir:
        args.gdal_dir = args.gdal_dir.resolve()
    os.chdir(ROOT)
    results = ROOT / "results"
    results.mkdir(exist_ok=True)
    build = ROOT / f"build/android-{args.abi}"
    build.mkdir(parents=True, exist_ok=True)
    triplet = "arm64-android" if args.abi == "arm64-v8a" else "x64-android"
    triple = "aarch64-linux-android" if args.abi == "arm64-v8a" else "x86_64-linux-android"
    adb = [str(args.sdk / "platform-tools" / ("adb.exe" if platform.system() == "Windows" else "adb")), "-s", args.serial]

    def run(command, runtime=False, capture=False):
        completed = subprocess.run([str(x) for x in command], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=900)
        with (results / "android.log").open("a") as log:
            log.write(completed.stdout)
        if completed.returncode or (runtime and "GDAL4K_PROBE_OK" not in completed.stdout):
            print(completed.stdout[-5000:])
            raise SystemExit(f"Android probe failed: {command[0]} ({completed.returncode})")
        return completed.stdout.strip() if capture else completed.stdout

    (results / "android.log").write_text("")
    actual_abi = run([*adb, "shell", "getprop", "ro.product.cpu.abi"], capture=True)
    if actual_abi != args.abi:
        raise SystemExit(f"Device ABI {actual_abi!r} does not match {args.abi}")
    run([args.gradle, "--console=plain", "stageJvmRuntime"])
    options = [f"-DCMAKE_TOOLCHAIN_FILE={args.vcpkg}/scripts/buildsystems/vcpkg.cmake", f"-DVCPKG_TARGET_TRIPLET={triplet}", f"-DVCPKG_CHAINLOAD_TOOLCHAIN_FILE={args.ndk}/build/cmake/android.toolchain.cmake", f"-DANDROID_ABI={args.abi}", "-DANDROID_PLATFORM=android-28", "-DCMAKE_BUILD_TYPE=Release"]
    if args.gdal_dir:
        options.append(f"-DGDAL_DIR={args.gdal_dir.resolve()}")
    run([args.cmake, "-S", "native", "-B", build, "-G", "Ninja", *options])
    run([args.cmake, "--build", build, "--parallel", "3"])
    prebuilts = next((args.ndk / "toolchains/llvm/prebuilt").iterdir())
    run([prebuilts / "bin/llvm-strip", "--strip-unneeded", build / "libgdal4k_probe.so"])
    d8 = args.sdk / "build-tools/36.0.0" / ("d8.bat" if platform.system() == "Windows" else "d8")
    dex = build / "dex"
    dex.mkdir(exist_ok=True)
    jars = [p for p in (ROOT / "build/jvm-runtime").glob("*.jar") if p.name.startswith(("gdal4k-platform-probe", "kotlin-stdlib-"))]
    run([d8, "--release", "--min-api", "28", "--output", dex, *jars])
    destination = "/data/local/tmp/gdal4k-platform-probe"
    run([*adb, "shell", "mkdir", "-p", destination])
    run([*adb, "push", build / "libgdal4k_probe.so", prebuilts / f"sysroot/usr/lib/{triple}/libc++_shared.so", dex / "classes.dex", destination + "/"])
    prefix = args.vcpkg / f"installed/{triplet}"
    gdal_data = args.gdal_dir.resolve().parents[2] / "share/gdal" if args.gdal_dir else prefix / "share/gdal"
    run([*adb, "push", prefix / "share/proj", destination + "/"])
    run([*adb, "push", gdal_data, destination + "/"])
    command = f"LD_LIBRARY_PATH={destination} PROJ_DATA={destination}/proj GDAL_DATA={destination}/gdal CLASSPATH={destination}/classes.dex app_process -Dgdal4k.probe.library={destination}/libgdal4k_probe.so /system/bin dev.gdal4k.probe.MainKt"
    output = run([*adb, "shell", command], runtime=True)
    report = {"timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(), "abi": actual_abi, "androidVersion": run([*adb, "shell", "getprop", "ro.build.version.release"], capture=True), "pageSize": run([*adb, "shell", "getconf", "PAGE_SIZE"], capture=True), "status": "passed", "output": output, "scope": "Kotlin bytecode/JNI on Android ART; not AAR/APK/scoped-storage/real-device validation"}
    (results / "android.json").write_text(json.dumps(report, indent=2) + "\n")
    print(output, end="")


if __name__ == "__main__":
    main()
