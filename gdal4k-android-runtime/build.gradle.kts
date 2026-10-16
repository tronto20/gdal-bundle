plugins { id("com.android.library") version "8.11.2"; `maven-publish` }
repositories { google(); mavenCentral() }
android {
    namespace = "dev.gdal4k.android.runtime"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    sourceSets["main"].jniLibs.srcDir(rootProject.file("gdal4k-binary/build/android-runtime/jniLibs"))
    publishing { singleVariant("release") }
}
val androidAbis = providers.gradleProperty("gdal4kAndroidAbis").orElse("arm64-v8a,armeabi-v7a,x86,x86_64")
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(androidAbis.get().split(',').map { abi -> ":gdal4k-binary:buildEngine" + "android-$abi".split('-','_').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } })
}
afterEvaluate { publishing.publications.create<MavenPublication>("androidRuntime") { from(components["release"]) } }
