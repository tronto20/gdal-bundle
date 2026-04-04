#!/usr/bin/env python3

from __future__ import annotations

import argparse
import lzma
import tarfile
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Package a GDAL bundle directory as a TXZ archive.")
    parser.add_argument("--source-dir", required=True, help="Directory to package.")
    parser.add_argument("--output-file", required=True, help="TXZ archive to create.")
    parser.add_argument(
        "--arcname",
        default="gdal",
        help="Top-level archive name to use inside the txz file (default: gdal).",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    source_dir = Path(args.source_dir)
    output_file = Path(args.output_file)
    arcname_prefix = args.arcname.strip()

    if not source_dir.exists() or not source_dir.is_dir():
        raise SystemExit(f"Source directory does not exist: {source_dir}")

    output_file.parent.mkdir(parents=True, exist_ok=True)

    # Use the strongest practical xz preset to minimize distribution size.
    with tarfile.open(output_file, mode="w:xz", preset=9 | lzma.PRESET_EXTREME) as archive:
        for child in sorted(source_dir.iterdir(), key=lambda path: path.name):
            arcname = child.name if not arcname_prefix else f"{arcname_prefix}/{child.name}"
            archive.add(child, arcname=arcname, recursive=True)


if __name__ == "__main__":
    main()
