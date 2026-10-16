plugins { id("com.android.library") version "8.11.2"; `maven-publish` }
repositories { google(); mavenCentral() }
version = providers.gradleProperty("gdal4kVersion").orElse("1.0.1-SNAPSHOT").get()
android {
    namespace = "dev.gdal4k.android.runtime"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    sourceSets["main"].apply {
        jniLibs.srcDir(rootProject.file("gdal4k/build/android-runtime/jniLibs"))
        assets.srcDir(rootProject.file("gdal4k/build/android-runtime/assets"))
    }
    publishing { singleVariant("release") }
}
tasks.matching { it.name.startsWith("merge") && (it.name.endsWith("JniLibFolders") || it.name.endsWith("Assets")) }.configureEach {
    doFirst {
        check(rootProject.file("gdal4k/build/android-runtime/assets/gdal4k/manifest.txt").isFile &&
            rootProject.file("gdal4k/build/android-runtime/jniLibs").walkTopDown().any { it.name == "libgdal4k.so" }) {
            "Stage Android GDAL binaries/data using gdal4k/tools/package-android.py before packaging an AAR"
        }
    }
}
afterEvaluate {
    publishing.publications.create<MavenPublication>("androidRuntime") { from(components["release"]) }
}
