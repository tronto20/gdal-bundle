plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(libs.versions.jvm.jdk.get().toInt())
    jvm()
}
