#!/usr/bin/env python3
"""Build and run the host JNI/Native and browser/Node feasibility probes.

Every runtime must exit successfully AND emit GDAL4K_PROBE_OK. Missing SDKs,
dependencies, or entry points are failures, never silently skipped successes.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
SENTINEL = "GDAL4K_PROBE_OK"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cmake", default="cmake")
    parser.add_argument("--gradle", default=str(ROOT / ("gradlew.bat" if platform.system() == "Windows" else "gradlew")))
    args = parser.parse_args()
    os.chdir(ROOT)
    embedded = (ROOT / "native/sample_tif.h").read_text().split("{", 1)[1].split("}", 1)[0]
    if bytes(map(int, re.findall(r"\d+", embedded))) != (ROOT / "fixtures/sample.tif").read_bytes():
        raise SystemExit("C and web TIFF fixtures differ")
    (ROOT / "results").mkdir(exist_ok=True)
    target = {("Darwin", "arm64"): "macosArm64", ("Linux", "x86_64"): "linuxX64", ("Linux", "aarch64"): "linuxArm64", ("Windows", "AMD64"): "mingwX64"}.get((platform.system(), platform.machine()))
    if target is None:
        raise SystemExit(f"Unsupported probe host: {platform.system()}/{platform.machine()}")
    results = []

    def run(name, command, runtime=False, env=None):
        log = ROOT / "results" / f"{name}.log"
        with log.open("w") as output:
            completed = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, env=env, timeout=900)
        text = log.read_text(errors="replace")
        passed = completed.returncode == 0 and (not runtime or SENTINEL in text)
        results.append({"name": name, "kind": "execution" if runtime else "build", "status": "passed" if passed else "failed", "exitCode": completed.returncode, "log": str(log.relative_to(ROOT))})
        print(f"{name}: {results[-1]['status']}", flush=True)
        if not passed:
            print(text[-5000:])
            save()
            raise SystemExit(1)

    def save():
        report = {"timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(), "host": f"{platform.system()}/{platform.machine()}", "fixtureSha256": hashlib.sha256((ROOT / "fixtures/sample.tif").read_bytes()).hexdigest(), "scope": "GDAL connection feasibility; production gdal4k API/packaging and full driver parity are not covered; WASI excluded", "results": results}
        (ROOT / "results/local.json").write_text(json.dumps(report, indent=2) + "\n")

    options = ["-DCMAKE_C_COMPILER=gcc", "-DCMAKE_CXX_COMPILER=g++"] if platform.system() == "Windows" else []
    run("native-build", [args.cmake, "-S", "native", "-B", "build/native", "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release", *options])
    run("native-link", [args.cmake, "--build", "build/native", "--parallel", "4"])
    run("kotlin-build", [args.gradle, "--console=plain", "jvmJar", f"linkDebugExecutable{target[0].upper() + target[1:]}", "compileDevelopmentExecutableKotlinJs", "compileDevelopmentExecutableKotlinWasmJs"])
    run("jvm", [args.gradle, "--console=plain", "probeJvm"], True)
    env = dict(os.environ)
    key = "DYLD_LIBRARY_PATH" if platform.system() == "Darwin" else "LD_LIBRARY_PATH"
    env[key] = str(ROOT / "build/native") + os.pathsep + env.get(key, "")
    if platform.system() == "Windows":
        env["PATH"] = str(ROOT / "build/native") + os.pathsep + env["PATH"]
    executable = ROOT / f"build/bin/{target}/debugExecutable/gdal4k-platform-probe{'.exe' if platform.system() == 'Windows' else '.kexe'}"
    run("native", [str(executable)], True, env)
    for target in ("js", "wasmJs"):
        extension = "js" if target == "js" else "mjs"
        run(f"{target}-node", ["node", "web/run-node.mjs", f"build/compileSync/{target}/main/developmentExecutable/kotlin/gdal4k-platform-probe.{extension}"], True)
    run("web-browser", ["node", "web/run-browser.mjs"], True)
    save()


if __name__ == "__main__":
    main()
