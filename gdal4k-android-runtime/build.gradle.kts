plugins { id("com.android.library") version "8.11.2"; `maven-publish` }
repositories { google(); mavenCentral() }
val supportedAndroidAbis = setOf("arm64-v8a", "x86_64")
val androidAbis = providers.gradleProperty("gdal4kAndroidAbis").orElse("arm64-v8a,x86_64").get().split(',').map(String::trim).distinct()
require(androidAbis.isNotEmpty() && androidAbis.all { it in supportedAndroidAbis }) {
    "gdal4k supports only 64-bit Android ABIs: arm64-v8a, x86_64"
}
android {
    namespace = "dev.gdal4k.android.runtime"
    compileSdk = 35
    defaultConfig {
        minSdk = 28
        ndk { abiFilters += androidAbis }
    }
    sourceSets["main"].jniLibs.srcDir(rootProject.file("gdal4k-binary/build/android-runtime/jniLibs"))
    // A library AAR can retain jniLibs outside ndk.abiFilters, including stale producer outputs.
    packaging.jniLibs.excludes += (listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86") - androidAbis.toSet()).map { "**/$it/**" }
    publishing { singleVariant("release") }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(androidAbis.map { abi -> ":gdal4k-binary:buildEngine" + "android-$abi".split('-','_').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } })
}
afterEvaluate { publishing.publications.create<MavenPublication>("androidRuntime") { from(components["release"]) } }
