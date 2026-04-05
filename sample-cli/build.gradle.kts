import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.beryx.runtime)
}

val gdalVersion = providers.gradleProperty("gdalVersion")
    .orElse("3.9.0")
    .get()
val gdal4kVersion = providers.gradleProperty("gdal4kVersion")
    .orElse("1.0.1-SNAPSHOT")
    .get()
val gdal4kPackageVersion = "$gdalVersion-$gdal4kVersion"

repositories {
    mavenCentral()
    maven {
        name = "gdal4kPackages"
        val packagesUrl = providers.gradleProperty("gdal4kPackagesUrl")
            .orElse(providers.environmentVariable("GITHUB_PACKAGES_URL"))
            .orElse(
                if (gdal4kPackageVersion.endsWith("SNAPSHOT")) {
                    "https://central.sonatype.com/repository/maven-snapshots/"
                } else {
                    "https://repo1.maven.org/maven2/"
                },
            )
            .get()
        url = uri(packagesUrl)
        val githubUsername = providers.gradleProperty("GITHUB_USERNAME").orNull
            ?: providers.environmentVariable("GITHUB_USERNAME").orNull
        val githubToken = providers.gradleProperty("GITHUB_TOKEN").orNull
            ?: providers.environmentVariable("GITHUB_TOKEN").orNull
        if (
            uri(packagesUrl).scheme != "file" &&
            uri(packagesUrl).host?.contains("github", ignoreCase = true) == true &&
            !githubUsername.isNullOrBlank() &&
            !githubToken.isNullOrBlank()
        ) {
            credentials {
                username = githubUsername
                password = githubToken
            }
        }
    }
}

group = "dev.tronto.gdal4k.sample"
version = providers.gradleProperty("sampleCliVersion").orElse("0.1.0-SNAPSHOT").get()

val gdal4kGroup = providers.gradleProperty("gdal4kGroup")
    .orElse("dev.tronto.gdal4k")
    .get()

fun currentGdalPlatformClassifier(): String {
    val osName = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()

    return when {
        osName.contains("mac") && (arch == "aarch64" || arch == "arm64") -> "macos-arm64"
        osName.contains("linux") && (arch == "aarch64" || arch == "arm64") -> "linux-arm64"
        osName.contains("linux") && (arch == "x86_64" || arch == "amd64") -> "linux-amd64"
        osName.contains("windows") && (arch == "x86_64" || arch == "amd64") -> "windows-amd64"
        else -> error("Unsupported host platform: os.name=$osName, os.arch=$arch")
    }
}

val currentGdalPlatformClassifier = currentGdalPlatformClassifier()

fun currentInstallerType(classifier: String): String {
    return when (classifier) {
        "macos-arm64" -> "pkg"
        "linux-amd64", "linux-arm64" -> "deb"
        "windows-amd64" -> "msi"
        else -> error("Unsupported platform for installer packaging: $classifier")
    }
}

fun currentJPackageResourceDir(classifier: String): File {
    val resourceDirName = when (classifier) {
        "macos-arm64" -> "macos"
        "linux-amd64", "linux-arm64" -> "linux"
        "windows-amd64" -> "windows"
        else -> error("Unsupported platform for installer resources: $classifier")
    }

    return file("src/jpackage/$resourceDirName")
}

fun normalizedJpackageVersion(version: String): String {
    val candidate = version.trim()
        .substringBefore('-')
        .substringBefore('+')
    val parts = candidate.split('.')
    return if (
        parts.isNotEmpty() &&
        parts.size <= 3 &&
        parts.all { part -> part.isNotEmpty() && part.all(Char::isDigit) } &&
        parts.first().toIntOrNull()?.let { it > 0 } == true
    ) {
        candidate
    } else {
        "1.0.0"
    }
}

dependencies {
    implementation("$gdal4kGroup:gdal4k-binary:$gdal4kPackageVersion:$currentGdalPlatformClassifier")
}

kotlin {
    jvmToolchain(libs.versions.jvm.jdk.get().toInt())
}

application {
    mainClass.set("dev.gdal4k.sample.cli.MainKt")
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvm.compatibility.get()))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(libs.versions.jvm.compatibility.get().toInt())
}

runtime {
    options = listOf(
        "--strip-debug",
        "--compress",
        "2",
        "--no-header-files",
        "--no-man-pages",
    )

    jpackage {
        appVersion = normalizedJpackageVersion(project.version.toString())
        imageName = "sample-cli"
        installerName = "sample-cli"
        outputDir = "distributions"
        installerType = currentInstallerType(currentGdalPlatformClassifier)
        resourceDir = currentJPackageResourceDir(currentGdalPlatformClassifier)

        if (currentGdalPlatformClassifier == "windows-amd64") {
            imageOptions = listOf("--win-console")
        }
    }
}

tasks.register("packageDistribution") {
    group = "distribution"
    description = "Packages the installable sample-cli distribution for the current host platform."
    dependsOn(tasks.named("jpackage"))
}
