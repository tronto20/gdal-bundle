import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.serialization)
    id("com.android.kotlin.multiplatform.library") version "8.11.2"
    `maven-publish`
}
repositories { google(); mavenCentral() }
version = providers.gradleProperty("gdal4kVersion").orElse("1.0.1-SNAPSHOT").get()
val bridgePrefix = providers.gradleProperty("gdal4kBridgePrefix")
    .orElse(rootProject.layout.projectDirectory.dir("gdal4k/build/bridge").asFile.absolutePath)
kotlin {
    jvmToolchain(21)
    jvm()
    androidLibrary {
        namespace = "dev.gdal4k"
        compileSdk = 35
        minSdk = 28
        withHostTest {}
    }
    js { nodejs(); browser() }
    wasmJs { nodejs(); browser() }
    macosArm64(); macosX64()
    linuxX64(); linuxArm64(); mingwX64()
    iosArm64(); iosSimulatorArm64(); iosX64()
    applyDefaultHierarchyTemplate {
        common { group("jni") { withCompilations { it.target.name == "jvm" || it.target.name == "android" } } }
    }
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        androidMain.dependencies { api(project(":gdal4k-android-runtime")) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations["main"].cinterops.create("gdal4k") {
            definitionFile.set(layout.projectDirectory.file("src/nativeInterop/cinterop/gdal4k.def"))
            includeDirs("native")
        }
        binaries.all {
            linkerOpts("-L${bridgePrefix.get()}", "-lgdal4k")
            if (konanTarget.family.isAppleFamily) linkerOpts("-rpath", bridgePrefix.get())
            if (konanTarget.family.name == "LINUX") linkerOpts("-Wl,--allow-shlib-undefined")
        }
    }
}
val npmCommand = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"
val installWebRuntime by tasks.registering(Exec::class) {
    workingDir("web")
    commandLine(npmCommand, "ci", "--ignore-scripts")
    inputs.files("web/package.json", "web/package-lock.json")
    outputs.dir("web/node_modules")
}
val webRuntime by tasks.registering(Zip::class) {
    dependsOn(installWebRuntime)
    from("web") { include("*.mjs") }
    from("web/node_modules/gdal3.js/dist/package") {
        include("gdal3.js", "gdal3.node.js", "gdal3WebAssembly.wasm", "gdal3WebAssembly.data")
    }
    from("web/node_modules/gdal3.js/LICENSE") { into("licenses/gdal3.js") }
    from("web/node_modules/gdal3.js/README.md") { into("licenses/gdal3.js") }
    archiveFileName.set("gdal4k-web.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}
publishing.publications.withType<MavenPublication>().matching { it.name == "kotlinMultiplatform" }.configureEach {
    artifact(webRuntime) { classifier = "web-runtime" }
}
