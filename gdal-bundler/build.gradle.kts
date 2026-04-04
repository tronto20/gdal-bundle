import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Zip
import dev.gdal4k.gdalbuild.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("maven-publish")
    base
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(libs.versions.jvm.jdk.get().toInt())
    jvm()

    sourceSets {
        val gdalPlatform = currentGdalPlatform()
        val gdalBundleRoot = layout.buildDirectory.dir("gdal-bundle/${gdalPlatform.classifier}/gdal")
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

val skipNativeBundleBuild = providers.gradleProperty("skipNativeBundleBuild")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
val defaultWorkDir = layout.buildDirectory.dir("gdal-work").map { it.asFile.absolutePath }
val defaultOutputDir = layout.buildDirectory.dir("gdal-bundle/${currentGdalPlatform().classifier}")
val defaultCondaInstallDir = layout.buildDirectory.dir("conda")
val defaultCondaInstallDirProvider = providers.environmentVariable("GDAL4K_CONDA_INSTALL_DIR")
    .map { layout.projectDirectory.dir(it) }
    .orElse(defaultCondaInstallDir)
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
    workDir.convention(
        providers.environmentVariable("GDAL4K_WORK_DIR").orElse(defaultWorkDir),
    )
    gdalVersion.convention("3.12.2")
    libkmlVersion.convention("1.3.0")
}

tasks.withType<GdalBundleTask>().configureEach {
    scriptFile.set(layout.projectDirectory.file("scripts/bundle_gdal.py"))
    outputDir.convention(defaultOutputDir)
    codesignIdentity.convention(providers.environmentVariable("CODESIGN_IDENTITY"))
}

val linuxAmd64BundleZip by tasks.registering(Zip::class) {
    group = "publishing"
    description = "Package the Linux amd64 GDAL bundle as a ZIP archive."
    from(layout.buildDirectory.dir("gdal-bundle/linux-amd64"))
    archiveBaseName.set("gdal4k-binary")
    archiveClassifier.set("linux-amd64")
    archiveExtension.set("zip")
    destinationDirectory.set(layout.buildDirectory.dir("published-bundles"))
}

val linuxArm64BundleZip by tasks.registering(Zip::class) {
    group = "publishing"
    description = "Package the Linux arm64 GDAL bundle as a ZIP archive."
    from(layout.buildDirectory.dir("gdal-bundle/linux-arm64"))
    archiveBaseName.set("gdal4k-binary")
    archiveClassifier.set("linux-arm64")
    archiveExtension.set("zip")
    destinationDirectory.set(layout.buildDirectory.dir("published-bundles"))
}

val macosArm64BundleZip by tasks.registering(Zip::class) {
    group = "publishing"
    description = "Package the macOS arm64 GDAL bundle as a ZIP archive."
    from(layout.buildDirectory.dir("gdal-bundle/macos-arm64"))
    archiveBaseName.set("gdal4k-binary")
    archiveClassifier.set("macos-arm64")
    archiveExtension.set("zip")
    destinationDirectory.set(layout.buildDirectory.dir("published-bundles"))
}

val windowsAmd64BundleZip by tasks.registering(Zip::class) {
    group = "publishing"
    description = "Package the Windows amd64 GDAL bundle as a ZIP archive."
    from(layout.buildDirectory.dir("gdal-bundle/windows-amd64"))
    archiveBaseName.set("gdal4k-binary")
    archiveClassifier.set("windows-amd64")
    archiveExtension.set("zip")
    destinationDirectory.set(layout.buildDirectory.dir("published-bundles"))
}

val windowsArm64BundleZip by tasks.registering(Zip::class) {
    group = "publishing"
    description = "Package the Windows arm64 GDAL bundle as a ZIP archive."
    from(layout.buildDirectory.dir("gdal-bundle/windows-arm64"))
    archiveBaseName.set("gdal4k-binary")
    archiveClassifier.set("windows-arm64")
    archiveExtension.set("zip")
    destinationDirectory.set(layout.buildDirectory.dir("published-bundles"))
}

val cleanGdalWorkDir by tasks.registering(GdalCleanTask::class) {
    group = "gdal"
    description = "Delete the GDAL build work directory."
    workDir.convention(defaultWorkDir)
}

val installConda by tasks.registering(CondaInstallTask::class) {
    group = "gdal"
    description = "Install Miniforge (conda) into the local build directory."
    installDir.convention(defaultCondaInstallDirProvider)
    installerUrl.convention(defaultCondaInstallerUrlProvider)
}

val installGdalDeps by tasks.registering(GdalBuildTask::class) {
    group = "gdal"
    description = "Install GDAL build dependencies into the conda prefix."
    step.set("deps")
    dependsOn(installConda)
}

val buildLibkml by tasks.registering(GdalBuildTask::class) {
    group = "gdal"
    description = "Build libkml from source for the current platform."
    step.set("libkml")
    dependsOn(installGdalDeps)
}

val buildGdal by tasks.registering(GdalBuildTask::class) {
    group = "gdal"
    description = "Build GDAL from source for the current platform with JNI."
    step.set("gdal")
    dependsOn(buildLibkml)
}

val bundleGdal by tasks.registering(GdalBundleTask::class) {
    group = "gdal"
    description = "Bundle GDAL dylibs and data from a conda environment for the current platform."
}

val buildAndBundleGdal by tasks.registering {
    group = "gdal"
    description = "Build GDAL from source and bundle the current platform artifacts."
    dependsOn(buildGdal, bundleGdal)
}

val buildLibkmlMacos by tasks.registering {
    group = "gdal"
    description = "Compatibility alias for buildLibkml."
    dependsOn(buildLibkml)
}

val bundleGdalMacos by tasks.registering {
    group = "gdal"
    description = "Compatibility alias for bundleGdal."
    dependsOn(bundleGdal)
}

val buildAndBundleGdalMacos by tasks.registering {
    group = "gdal"
    description = "Compatibility alias for buildAndBundleGdal."
    dependsOn(buildAndBundleGdal)
}

val buildGdalMacos by tasks.registering {
    group = "gdal"
    description = "Compatibility alias for buildGdal."
    dependsOn(buildGdal)
}

bundleGdal.configure {
    mustRunAfter(buildGdal)
}

tasks.named("compileKotlinJvm").configure {
    if (!skipNativeBundleBuild.get()) {
        dependsOn(buildAndBundleGdal)
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvm.compatibility.get()))
    }
}

extensions.configure<PublishingExtension> {
    publications.withType<MavenPublication>().matching { it.name == "jvm" }.configureEach {
        artifact(linuxAmd64BundleZip)
        artifact(linuxArm64BundleZip)
        artifact(macosArm64BundleZip)
        artifact(windowsAmd64BundleZip)
        artifact(windowsArm64BundleZip)
        pom {
            name.set("gdal4k-binary")
            description.set("GDAL JVM runtime bridge and published native bundle archives")
        }
    }
}
