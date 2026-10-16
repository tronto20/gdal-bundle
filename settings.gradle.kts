pluginManagement { repositories { google(); gradlePluginPortal(); mavenCentral() } }
rootProject.name = "gdal-bundle"
include(":gdal4k", ":gdal4k-contract", ":gdal4k-android-test", ":gdal4k-android-runtime")
if (providers.gradleProperty("portableOnly").orNull != "true") {
    include(":gdal4k-runtime", ":gdal4k-binary")
}
