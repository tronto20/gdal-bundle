import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.serialization)
    id("com.android.kotlin.multiplatform.library") version "8.11.2"
    `maven-publish`
}
repositories { google(); mavenCentral() }
val pythonCommand = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
fun engineTask(target: String) = tasks.register<Exec>("buildEngine" + target.split('-','_').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }) {
    group = "GDAL runtime"
    // These outputs can arrive from verified CI artifacts. Gradle's first-run
    // stale-output cleanup must not delete them before the producer checks hashes.
    doNotTrackState("The engine producer fingerprints sources and verifies every artifact hash")
    workingDir(rootDir)
    commandLine(pythonCommand, "gdal4k-binary/tools/build-engine.py", target)
    environment("GDAL4K_BINARY_VERSION", rootProject.version.toString())
    inputs.files(fileTree("ports") { exclude("**/__pycache__/**") })
    inputs.dir("engine")
    inputs.files(fileTree("tools") { include("*.py") })
    inputs.dir("web")
    inputs.dir(rootProject.file("gdal4k/native"))
    outputs.file(layout.buildDirectory.file("engine/$target/manifest.json"))
    if (target == "web") outputs.dir(layout.buildDirectory.dir("generated/web"))
    else {
        outputs.file(layout.buildDirectory.file("engine/$target/libgdal4k.a"))
        outputs.file(layout.buildDirectory.file("engine/$target/native-linker-options.json"))
        if (target.startsWith("android")) outputs.file(layout.buildDirectory.file("android-runtime/jniLibs/${target.removePrefix("android-")}/libgdal4k.so"))
        else if (!target.startsWith("ios")) outputs.file(layout.buildDirectory.file("jvm-resources/gdal4k/$target/${if (target.startsWith("windows")) "gdal4k.dll" else if (target.startsWith("macos")) "libgdal4k.dylib" else "libgdal4k.so"}"))
    }
}
val engineTargets = listOf("macos-arm64", "linux-amd64", "linux-arm64", "windows-amd64", "ios-arm64", "ios-simulator-arm64", "android-arm64-v8a", "web")
engineTargets.forEach(::engineTask)

kotlin {
    jvmToolchain(21)
    jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    androidLibrary { namespace = "dev.gdal4k.binary"; compileSdk = 35; minSdk = 28 }
    js { nodejs(); browser() }; wasmJs { nodejs(); browser() }
    macosArm64(); linuxX64(); linuxArm64(); mingwX64()
    iosArm64(); iosSimulatorArm64()
    applyDefaultHierarchyTemplate {
        common {
            group("jni") { withCompilations { it.target.name in listOf("jvm", "android") } }
            group("web") { withJs(); withWasmJs() }
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":gdal4k-runtime"))
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        androidMain.dependencies {
            implementation("androidx.startup:startup-runtime:1.2.0")
            implementation("androidx.annotation:annotation:1.9.1")
        }
        getByName("webMain").dependencies { api("org.jetbrains.kotlinx:kotlinx-browser:0.5.0") }
    }
    targets.withType<KotlinNativeTarget>().configureEach {
        val targetName = name
        compilations["main"].defaultSourceSet.kotlin.srcDir("src/nativeInteropMain/kotlin")
        val prepareDefinition = tasks.register("prepare${name.replaceFirstChar(Char::uppercaseChar)}Definition") {
            val destination = layout.buildDirectory.file("cinterop/$targetName/gdal4k.def")
            outputs.file(destination)
            doLast {
                destination.get().asFile.apply {
                    parentFile.mkdirs()
                    writeText("headers = gdal4k.h\ncompilerOpts = -DGDAL4K_STATIC\npackage = dev.gdal4k.cinterop\n")
                }
            }
        }
        compilations["main"].cinterops.create("gdal4k") {
            definitionFile.set(layout.buildDirectory.file("cinterop/$targetName/gdal4k.def"))
            includeDirs(rootProject.file("gdal4k/native"))
            tasks.named(interopProcessingTaskName).configure { dependsOn(prepareDefinition) }
        }
    }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
