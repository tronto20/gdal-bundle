import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.zip.ZipFile
import dev.gdal4k.gdalbuild.currentGdalPlatform

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.plugin.compose)
}

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
}


val gdalBundleRoot = project(":gdal4k-binary").layout.buildDirectory.dir(
    "gdal-bundle/${currentGdalPlatform().classifier}/gdal",
)
val appResourceRoot = layout.buildDirectory.dir("app-resources")
val gdalResourcesDir = appResourceRoot.map { it.dir("macos/gdal") }
val jcefVersion = providers.gradleProperty("jcefVersion")
    .orElse("111.2.1-g870da30-chromium-111.0.5563.64_with_all_binaries-api-1.12")
val jcefArch = providers.gradleProperty("jcefArch")
    .orElse(
        providers.provider {
            when (val arch = System.getProperty("os.arch")) {
                "aarch64", "arm64" -> "aarch64"
                "x86_64", "amd64" -> "x86_64"
                else -> arch
            }
        },
    )
val jcefNativeArchive = jcefArch.map { "native_osx_${it}.tar.gz" }
val jcefBundleRoot = layout.buildDirectory.dir("jcef-bundle/jcef")
val jcefResourcesDir = appResourceRoot.map { it.dir("macos/jcef") }
val jcefBundler by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(jcefBundler.name, "org.jetbrains.intellij.deps.jcef:jcef:${jcefVersion.get()}")
}

abstract class EnsureWritableTask : DefaultTask() {
    @get:Optional
    @get:InputDirectory
    abstract val directory: DirectoryProperty

    @TaskAction
    fun run() {
        val dir = directory.orNull?.asFile ?: return
        if (!dir.exists()) {
            return
        }
        dir.walkTopDown().forEach { file ->
            if (!file.canWrite()) {
                file.setWritable(true, true)
            }
        }
    }
}

abstract class JcefBundleTask : DefaultTask() {
    @get:InputFile
    abstract val jcefJar: RegularFileProperty

    @get:Input
    abstract val nativeArchiveName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val osName = System.getProperty("os.name").lowercase()
        if (!osName.contains("mac")) {
            throw GradleException("bundleJcefMacos only supports macOS hosts.")
        }

        val jarFile = jcefJar.get().asFile
        val archiveName = nativeArchiveName.get()
        val output = outputDir.get().asFile
        val tarFile = temporaryDir.resolve(archiveName)

        ZipFile(jarFile).use { zip ->
            val entry = zip.getEntry(archiveName)
                ?: throw GradleException("JCEF archive $archiveName not found in ${jarFile.name}.")
            zip.getInputStream(entry).use { input ->
                tarFile.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
        }

        project.sync {
            from(project.tarTree(project.resources.gzip(tarFile)))
            into(output)
        }
    }
}

val makeGdalResourcesWritable by tasks.registering(EnsureWritableTask::class) {
    dependsOn(":gdal4k-binary:buildAndBundleGdal")
    directory.set(gdalBundleRoot)
}

val syncGdalResources by tasks.registering(Sync::class) {
    dependsOn(":gdal4k-binary:buildAndBundleGdal", makeGdalResourcesWritable)
    from(gdalBundleRoot)
    into(gdalResourcesDir)
    exclude("*.jar")
}

val jcefJarProvider = configurations.named(jcefBundler.name).map { it.singleFile }
val bundleJcefMacos by tasks.registering(JcefBundleTask::class) {
    description = "Extract JCEF macOS natives into the JCEF bundle directory."
    group = "jcef"
    jcefJar.set(layout.file(jcefJarProvider))
    nativeArchiveName.set(jcefNativeArchive)
    outputDir.set(jcefBundleRoot)
}

val syncJcefResources by tasks.registering(Sync::class) {
    dependsOn(bundleJcefMacos)
    from(jcefBundleRoot)
    into(jcefResourcesDir)
}
kotlin {
    jvmToolchain(libs.versions.jvm.jdk.get().toInt())
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.desktop.currentOs)
        }

        jvmMain.dependencies {
            implementation(project(":gdal4k-binary"))
            implementation("org.jetbrains.intellij.deps.jcef:jcef:${jcefVersion.get()}")

        }
    }

}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvm.compatibility.get()))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(libs.versions.jvm.compatibility.get().toInt())
}

compose.desktop {
    application {
        mainClass = "com.gdal.bundle.sample.MainKt"
        jvmArgs(
            "--enable-native-access=ALL-UNNAMED",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "gdal-info-sample"
            packageVersion = "1.0.0"
            appResourcesRootDir.set(appResourceRoot)
        }
    }
}
//
//val gdalBundleTasks = setOf(
//    "createDistributable",
//    "createReleaseDistributable",
//    "createRuntimeImage",
//    "createReleaseRuntimeImage",
//    "package",
//    "packageDistributionForCurrentOS",
//    "packageReleaseDistributionForCurrentOS",
//    "packageDmg",
//    "packageReleaseDmg",
//    "run",
//    "runDistributable",
//    "runRelease",
//    "runReleaseDistributable",
//)
//
//tasks.matching { it.name in gdalBundleTasks }.configureEach {
//    dependsOn(":gdal4k-binary:bundleGdal")
//}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(syncGdalResources, syncJcefResources)
}
