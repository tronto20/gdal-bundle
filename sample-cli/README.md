# sample-cli

Standalone JVM CLI sample that consumes `gdal4k-binary` from Maven Central snapshots or a custom repository and packages an installable native app.

## What it does

- depends on `dev.tronto.gdal4k:gdal4k-binary:<gdalVersion>-<gdal4kVersion>:<os>_<arch>`
- loads GDAL through `Gdal4kBinary.prepare()` at startup, extracting the bundle into a user cache on first run
- prints `gdalinfo` output for the dataset you pass in
- runs `sample-cli smoke` to verify the current GDAL surface set after installation
- builds an installable native package for the current host with `packageDistribution`
- exposes a `sample-cli` command on the installed machine
- provides a `sample-cli-uninstall` helper on macOS and Linux

## Build

```bash
./gradlew -p sample-cli packageDistribution
```

Optional version / repository overrides:

```bash
./gradlew -p sample-cli packageDistribution \
  -PgdalVersion=3.9.0 \
  -Pgdal4kVersion=1.0.1-SNAPSHOT \
  -Pgdal4kPackagesUrl=file:///path/to/local-maven-repo \
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
Run `sample-cli smoke` to exercise the bundled raster, GCP, vector, geometry, and spatial reference surfaces.

Uninstall:

- macOS and Linux: `sudo sample-cli-uninstall`
- Windows: use Add or Remove Programs, which also removes the PATH entry

The installer file is written to `build/distributions` for the current host platform.

## Notes

- This project is JVM-only for now.
- The CLI is intentionally small so the same core `Dataset` code can later be reused by a future GUI multiplatform app.
- The installed app contains the JRE, the launcher, and the `gdal4k-binary` payload, so it does not need a separate Java installation.
- You can still override the bundled runtime with `-Dgdal.bundle.dir` or `GDAL_BUNDLE_DIR` if needed.
- The published Maven version is `gdalVersion-gdal4kVersion`, so `gdalVersion=3.9.0` and `gdal4kVersion=1.0.1-SNAPSHOT` resolve `gdal4k-binary:3.9.0-1.0.1-SNAPSHOT`.
- To consume a local build or GitHub Packages mirror, override `gdal4kPackagesUrl` and, for GitHub Packages, provide `GITHUB_USERNAME` / `GITHUB_TOKEN` as Gradle properties or environment variables.
