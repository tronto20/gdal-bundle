import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("com.android.kotlin.multiplatform.library") version "8.11.2"
    `maven-publish`
}
repositories { google(); mavenCentral() }
kotlin {
    jvmToolchain(21)
    jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    androidLibrary {
        namespace = "dev.gdal4k"
        compileSdk = 35; minSdk = 28
        withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }
    js { nodejs { testTask { useMocha { timeout = "120s" } } }; browser { testTask { useKarma { useChromeHeadless() } } } }
    wasmJs { nodejs { testTask { useMocha { timeout = "240s" } } }; browser { testTask { useKarma { useChromeHeadless() } } } }
    macosArm64(); macosX64(); linuxX64(); linuxArm64(); mingwX64(); iosArm64()
    iosSimulatorArm64 { testRuns["test"].deviceId = providers.gradleProperty("iosDevice").orElse("iPhone 16").get() }
    iosX64()
    sourceSets {
        commonMain.dependencies { api(project(":gdal4k-binary")) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        getByName("androidDeviceTest").dependencies { implementation("androidx.test:runner:1.6.2") }
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
    testHostOs.contains("mac") && testHostX64 -> "macosX64"
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
    dependsOn(":gdal4k-binary:buildEngineWeb")
    inputs.dir(rootProject.project(":gdal4k-binary").layout.buildDirectory.dir("generated/web/assets"))
}
