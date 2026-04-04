import org.gradle.api.publish.PublishingExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("maven-publish")
}

repositories {
    mavenCentral()
}

extensions.configure<PublishingExtension> {
    publications.withType<org.gradle.api.publish.maven.MavenPublication>().configureEach {
        pom {
            name.set("gdal4k-runtime")
            description.set("Kotlin Multiplatform runtime API for GDAL")
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jvm.jdk.get().toInt())
    jvm()
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvm.compatibility.get()))
    }
}
