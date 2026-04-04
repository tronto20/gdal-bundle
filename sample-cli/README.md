# sample-cli

Standalone JVM CLI sample that consumes `gdal4k-binary` from GitHub Packages and packages an installable native app.

## What it does

- depends on `dev.gdal4k:gdal4k-binary:<version>:<os>_<arch>` from GitHub Packages
- loads GDAL through `Gdal4kBinary.prepare()` at startup, extracting the bundle into a user cache on first run
- prints `gdalinfo` output for the dataset you pass in
- runs `sample-cli smoke` to verify the current GDAL surface set after installation
- builds an installable native package for the current host with `packageDistribution`
- exposes a `sample-cli` command on the installed machine
- provides a `sample-cli-uninstall` helper on macOS and Linux

## Build

```bash
GITHUB_USERNAME=<your-user> \
GITHUB_TOKEN=<token-with-read-packages> \
./gradlew -p sample-cli packageDistribution
```

Optional version overrides:

```bash
./gradlew -p sample-cli packageDistribution \
  -PgdalVersion=3.9.0 \
  -PsampleCliVersion=0.1.0-SNAPSHOT
```

## Run

After the packaging task completes, install the native package from `build/distributions` and run the installed app.

Package types by platform:

- macOS arm64: `.pkg`
- Linux amd64: `.deb`
- Linux arm64: `.deb`
- Windows amd64: `.msi`

After installation, run `sample-cli info /path/to/dataset.tif`.
Run `sample-cli smoke` to exercise the bundled raster, vector, geometry, and spatial reference surfaces.

Uninstall:

- macOS and Linux: `sudo sample-cli-uninstall`
- Windows: use Add or Remove Programs, which also removes the PATH entry

The installer file is written to `build/distributions` for the current host platform.

## Notes

- This project is JVM-only for now.
- The CLI is intentionally small so the same core `Dataset` code can later be reused by a future GUI multiplatform app.
- The installed app contains the JRE, the launcher, and the `gdal4k-binary` payload, so it does not need a separate Java installation.
- You can still override the bundled runtime with `-Dgdal.bundle.dir` or `GDAL_BUNDLE_DIR` if needed.
