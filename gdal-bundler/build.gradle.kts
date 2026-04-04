import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
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
        val gdalBundleRoot = layout.buildDirectory.dir("gdal-bundle/${currentGdalPlatform().classifier}/gdal")
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
            implementation(libs.commons.compress)
            implementation(libs.xz)
        }
    }
}

private val currentPlatform = currentGdalPlatform()
val skipNativeBundleBuild = providers.gradleProperty("skipNativeBundleBuild")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
val skipCondaDepsInstallFlag = providers.gradleProperty("skipCondaDepsInstall")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
val defaultWorkDir = layout.buildDirectory.dir("gdal-work").map { it.asFile.absolutePath }
val defaultOutputDir = layout.buildDirectory.dir("gdal-bundle/${currentPlatform.classifier}")
val defaultPythonExecutable = providers.provider {
    findPythonInPath() ?: if (isWindows()) "python" else "python3"
}
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
    skipCondaDepsInstall.convention(skipCondaDepsInstallFlag)
}

tasks.withType<GdalBundleTask>().configureEach {
    scriptFile.set(layout.projectDirectory.file("scripts/bundle_gdal.py"))
    outputDir.convention(defaultOutputDir)
    codesignIdentity.convention(providers.environmentVariable("CODESIGN_IDENTITY"))
}

tasks.withType<GdalTxzPackageTask>().configureEach {
    mustRunAfter(bundleGdal)
}

fun registerBundleArchive(
    taskName: String,
    classifier: String,
    sourceDir: Provider<Directory>,
    descriptionText: String,
) = tasks.register(taskName, GdalTxzPackageTask::class) {
    group = "publishing"
    description = descriptionText
    scriptFile.set(layout.projectDirectory.file("scripts/package_gdal_txz.py"))
    this.sourceDir.set(sourceDir)
    outputFile.set(layout.buildDirectory.file("published-bundles/gdal4k-binary-$classifier.txz"))
    arcname.set("")
    pythonExecutable.convention(defaultPythonExecutable)
}

val linuxAmd64BundleTxz = registerBundleArchive(
    taskName = "linuxAmd64BundleTxz",
    classifier = "linux-amd64",
    sourceDir = layout.buildDirectory.dir("gdal-bundle/linux-amd64/gdal"),
    descriptionText = "Package the Linux amd64 GDAL bundle as a compressed TXZ archive.",
)

val linuxArm64BundleTxz = registerBundleArchive(
    taskName = "linuxArm64BundleTxz",
    classifier = "linux-arm64",
    sourceDir = layout.buildDirectory.dir("gdal-bundle/linux-arm64/gdal"),
    descriptionText = "Package the Linux arm64 GDAL bundle as a compressed TXZ archive.",
)

val macosArm64BundleTxz = registerBundleArchive(
    taskName = "macosArm64BundleTxz",
    classifier = "macos-arm64",
    sourceDir = layout.buildDirectory.dir("gdal-bundle/macos-arm64/gdal"),
    descriptionText = "Package the macOS arm64 GDAL bundle as a compressed TXZ archive.",
)

val windowsAmd64BundleTxz = registerBundleArchive(
    taskName = "windowsAmd64BundleTxz",
    classifier = "windows-amd64",
    sourceDir = layout.buildDirectory.dir("gdal-bundle/windows-amd64/gdal"),
    descriptionText = "Package the Windows amd64 GDAL bundle as a compressed TXZ archive.",
)

val currentBundleArchive = when (currentPlatform.classifier) {
    "linux-amd64" -> linuxAmd64BundleTxz
    "linux-arm64" -> linuxArm64BundleTxz
    "macos-arm64" -> macosArm64BundleTxz
    "windows-amd64" -> windowsAmd64BundleTxz
    else -> error("Unsupported current platform classifier: ${currentPlatform.classifier}")
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
    description = "Build GDAL from source, bundle the current platform artifacts, and package a TXZ archive."
    dependsOn(buildGdal, bundleGdal, currentBundleArchive)
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
        artifact(linuxAmd64BundleTxz.flatMap { it.outputFile }) {
            builtBy(linuxAmd64BundleTxz)
        }
        artifact(linuxArm64BundleTxz.flatMap { it.outputFile }) {
            builtBy(linuxArm64BundleTxz)
        }
        artifact(macosArm64BundleTxz.flatMap { it.outputFile }) {
            builtBy(macosArm64BundleTxz)
        }
        artifact(windowsAmd64BundleTxz.flatMap { it.outputFile }) {
            builtBy(windowsAmd64BundleTxz)
        }
        pom {
            name.set("gdal4k-binary")
            description.set("GDAL JVM runtime bridge and published native TXZ bundle archives")
        }
    }
}
