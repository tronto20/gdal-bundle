import dev.gdal4k.gdalbuild.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    base
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(libs.versions.jvm.jdk.get().toInt())
    jvm()

    sourceSets {
        val gdalBundleRoot = layout.buildDirectory.dir("gdal-bundle/gdal")
        val gdalJarDir = gdalBundleRoot.map { it.dir("share/java") }
        val gdalJarFiles = fileTree(gdalJarDir.get()) {
            include("gdal*.jar")
            exclude("*-sources.jar", "*-javadoc.jar")
        }

        commonMain.dependencies {
            api(project(":gdal4k-runtime"))
        }

        jvmMain.dependencies {
            api(gdalJarFiles)
        }
    }
}

val defaultWorkDir = layout.buildDirectory.dir("gdal-work").map { it.asFile.absolutePath }
val defaultOutputDir = layout.buildDirectory.dir("gdal-bundle")
val defaultCondaInstallDir = layout.buildDirectory.dir("conda")
val defaultCondaInstallerUrlProvider = providers.provider { defaultCondaInstallerUrl() }
val defaultCondaPrefixProvider = providers.provider {
    findCondaInPath()?.let { resolveCondaBase(it) }
        ?: defaultCondaInstallDir.get().asFile.absolutePath
}

tasks.withType<GdalBaseTask>().configureEach {
    condaPrefix.convention(
        providers.environmentVariable("CONDA_PREFIX").orElse(defaultCondaPrefixProvider)
    )
    condaExe.convention(providers.environmentVariable("CONDA_EXE"))
}

tasks.withType<GdalBuildTask>().configureEach {
    scriptFile.set(layout.projectDirectory.file("scripts/build_and_bundle_gdal_macos.sh"))
    workDir.convention(defaultWorkDir)
    gdalVersion.convention("3.12.2")
    libkmlVersion.convention("1.3.0")
}

tasks.withType<GdalBundleTask>().configureEach {
    scriptFile.set(layout.projectDirectory.file("scripts/bundle_gdal_macos.py"))
    outputDir.convention(defaultOutputDir)
    codesignIdentity.convention(providers.environmentVariable("CODESIGN_IDENTITY"))
}

val cleanGdalWorkDir by tasks.registering(GdalCleanTask::class) {
    group = "gdal"
    description = "Delete the GDAL build work directory."
    workDir.convention(defaultWorkDir)
}

val installConda by tasks.registering(CondaInstallTask::class) {
    group = "gdal"
    description = "Install Miniforge (conda) into the local build directory."
    installDir.convention(defaultCondaInstallDir)
    installerUrl.convention(defaultCondaInstallerUrlProvider)
}

val installGdalDeps by tasks.registering(GdalBuildTask::class) {
    group = "gdal"
    description = "Install GDAL build dependencies into the conda prefix."
    step.set("deps")
}

val buildLibkmlMacos by tasks.registering(GdalBuildTask::class) {
    group = "gdal"
    description = "Build libkml from source for macOS."
    step.set("libkml")
    dependsOn(installGdalDeps)
}

val buildGdalMacos by tasks.registering(GdalBuildTask::class) {
    group = "gdal"
    description = "Build GDAL from source for macOS with JNI."
    step.set("gdal")
    dependsOn(buildLibkmlMacos)
}

val bundleGdalMacos by tasks.registering(GdalBundleTask::class) {
    group = "gdal"
    description = "Bundle GDAL dylibs and data from a conda environment for macOS."
}

val buildAndBundleGdalMacos by tasks.registering {
    group = "gdal"
    description = "Build GDAL + libkml from source and bundle dylibs for macOS."
    dependsOn(buildGdalMacos, bundleGdalMacos)
}

bundleGdalMacos.configure {
    mustRunAfter(buildGdalMacos)
}

tasks.named("compileKotlinJvm").configure {
    dependsOn(bundleGdalMacos)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvm.compatibility.get()))
    }
}
