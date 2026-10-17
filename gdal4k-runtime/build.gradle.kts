import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.serialization)
    id("com.android.kotlin.multiplatform.library") version "8.11.2"
    `maven-publish`
}
repositories { google(); mavenCentral() }
kotlin {
    jvmToolchain(21)
    jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    androidLibrary {
        namespace = "dev.gdal4k.runtime"
        compileSdk = 35; minSdk = 28
        withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }
    js { nodejs { testTask { useMocha { timeout = "120s" } } }; browser { testTask { useKarma { useChromeHeadless() } } } }
    wasmJs { nodejs { testTask { useMocha { timeout = "240s" } } }; browser { testTask { useKarma { useChromeHeadless() } } } }
    macosArm64(); linuxX64(); linuxArm64(); mingwX64(); iosArm64()
    iosSimulatorArm64 { testRuns["test"].deviceId = providers.gradleProperty("iosDevice").orElse("iPhone 16").get() }
    applyDefaultHierarchyTemplate {
        common {
            group("jni") { withCompilations { it.target.name in listOf("jvm", "android") } }
            group("web") { withJs(); withWasmJs() }
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":gdal4k"))
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        androidMain.dependencies {
            implementation("androidx.startup:startup-runtime:1.2.0")
            implementation("androidx.annotation:annotation:1.9.1")
        }
        getByName("webMain").dependencies { api("org.jetbrains.kotlinx:kotlinx-browser:0.5.0") }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        getByName("androidDeviceTest").dependencies {
            implementation("androidx.test:runner:1.6.2")
            implementation(project(":gdal4k-android-runtime"))
        }
        for (sourceSet in listOf("macosArm64Test", "linuxX64Test", "linuxArm64Test", "mingwX64Test", "iosArm64Test", "iosSimulatorArm64Test", "jsTest", "wasmJsTest")) {
            getByName(sourceSet).dependencies { implementation(project(":gdal4k-engine")) }
        }
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

// The library has no JVM payload dependency. Its tests explicitly select the host.
val testHostOs = System.getProperty("os.name").lowercase()
val testHostArch = System.getProperty("os.arch").lowercase()
val testHostArm64 = testHostArch in listOf("aarch64", "arm64")
val testHostX64 = testHostArch in listOf("amd64", "x86_64")
val testBinary = when {
    testHostOs.contains("mac") && testHostArm64 -> "macosArm64"
    testHostOs.contains("linux") && testHostArm64 -> "linuxArm64Gnu"
    testHostOs.contains("linux") && testHostX64 -> "linuxX64Gnu"
    testHostOs.contains("windows") && testHostX64 -> "windowsX64"
    else -> error("Unsupported JVM test host: $testHostOs/$testHostArch")
}
dependencies {
    add("jvmTestRuntimeOnly", project(mapOf("path" to ":gdal4k-jvm-binaries", "configuration" to "${testBinary}RuntimeElements")))
}

// The runtime assets are outside Kotlin IR, so changes must invalidate web tests too.
tasks.matching { it.name in setOf("jsNodeTest", "jsBrowserTest", "wasmJsNodeTest", "wasmJsBrowserTest") }.configureEach {
    dependsOn(":gdal4k-engine:buildEngineWeb")
    inputs.dir(rootProject.project(":gdal4k-engine").layout.buildDirectory.dir("generated/web/assets"))
}
