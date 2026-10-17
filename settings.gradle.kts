pluginManagement { repositories { mavenCentral(); google(); gradlePluginPortal() } }
rootProject.name = "gdal4k"
include(":gdal4k-engine", ":gdal4k-jvm-binaries", ":gdal4k", ":gdal4k-runtime", ":gdal4k-android-runtime")
