pluginManagement { repositories { mavenCentral(); google(); gradlePluginPortal() } }
rootProject.name = "gdal-bundle"
include(":gdal4k", ":gdal4k-runtime", ":gdal4k-binary", ":gdal4k-android-runtime")
