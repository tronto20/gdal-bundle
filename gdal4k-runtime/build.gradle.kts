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
    androidLibrary { namespace = "dev.gdal4k.runtime"; compileSdk = 35; minSdk = 28 }
    js { nodejs(); browser() }; wasmJs { nodejs(); browser() }
    macosArm64(); linuxX64(); linuxArm64(); mingwX64()
    iosArm64(); iosSimulatorArm64()
    sourceSets.commonMain.dependencies { api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2") }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
