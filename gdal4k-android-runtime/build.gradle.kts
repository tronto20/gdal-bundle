plugins { id("com.android.library") version "8.11.2"; `maven-publish` }
repositories { google(); mavenCentral() }
val supportedAndroidAbis = setOf("arm64-v8a")
val androidAbis = providers.gradleProperty("gdal4kAndroidAbis").orElse("arm64-v8a").get().split(',').map(String::trim).distinct()
require(androidAbis.isNotEmpty() && androidAbis.all { it in supportedAndroidAbis }) {
    "gdal4k supports only Android ARM64: arm64-v8a"
}
android {
    namespace = "dev.gdal4k.android.runtime"
    compileSdk = 35
    defaultConfig {
        minSdk = 28
        ndk { abiFilters += androidAbis }
    }
    sourceSets["main"].jniLibs.srcDir(rootProject.file("gdal4k-engine/build/android-runtime/jniLibs"))
    // A library AAR can retain jniLibs outside ndk.abiFilters, including stale producer outputs.
    packaging.jniLibs.excludes += (listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86") - androidAbis.toSet()).map { "**/$it/**" }
    publishing { singleVariant("release") }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(androidAbis.map { abi -> ":gdal4k-engine:buildEngine" + "android-$abi".split('-','_').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } })
}
afterEvaluate {
    publishing.publications.create<MavenPublication>("androidRuntime") {
        artifactId = "gdal4k-engine-android-arm64"
        from(components["release"])
    }
}

apply(from = rootProject.file("gradle/engine-variants.gradle.kts"))
