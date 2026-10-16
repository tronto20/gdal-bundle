#!/usr/bin/env python3
"""Build the host bridge and run gdal4k's standard Gradle integration tests."""
import os
from pathlib import Path
import platform
import subprocess

ROOT = Path(__file__).resolve().parents[2]
os.chdir(ROOT)
bridge = ROOT / "gdal4k/build/bridge"
system = platform.system()
host = {("Darwin", "arm64"): "macosArm64", ("Darwin", "x86_64"): "macosX64",
        ("Linux", "x86_64"): "linuxX64", ("Windows", "AMD64"): "mingwX64"}.get((system, platform.machine()))
if not host:
    raise SystemExit("Run on a supported Kotlin/Native host; cross-compiled tests need their target environment.")
env = os.environ.copy()
env["PATH"] = str(bridge) + os.pathsep + env["PATH"]
if system == "Linux":
    env["LD_LIBRARY_PATH"] = str(bridge) + os.pathsep + env.get("LD_LIBRARY_PATH", "")
# Reuse the Chromium installed by verification/npm ci + playwright install.
if "CHROME_BIN" not in env:
    env["CHROME_BIN"] = subprocess.check_output([
        "node", "-e", 'console.log(require("./verification/node_modules/playwright").chromium.executablePath())'
    ], text=True).strip()
options = ["-DCMAKE_BUILD_TYPE=Release"]
if system == "Windows":
    options += ["-DCMAKE_C_COMPILER=gcc", "-DCMAKE_CXX_COMPILER=g++"]
subprocess.run(["cmake", "-S", "gdal4k/native", "-B", str(bridge), "-G", "Ninja", *options], env=env, check=True)
subprocess.run(["cmake", "--build", str(bridge), "--parallel", "3"], env=env, check=True)
gradle = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
subprocess.run([str(gradle), "-PportableOnly=true", f"-Pgdal4kBridgePrefix={bridge}", "--console=plain",
                ":gdal4k:jvmTest", f":gdal4k:{host}Test", ":gdal4k:jsNodeTest", ":gdal4k:wasmJsNodeTest",
                ":gdal4k:jsBrowserTest", ":gdal4k:wasmJsBrowserTest"], env=env, check=True)
