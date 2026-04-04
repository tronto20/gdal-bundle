
include(
    "gdal4k-runtime",
    "gdal4k-binary",
    "sample-app",
)

project(":gdal4k-binary").projectDir = file("gdal-bundler")

rootProject.name = "gdal-bundle"
