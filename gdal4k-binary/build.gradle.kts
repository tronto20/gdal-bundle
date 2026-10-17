import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.serialization)
    id("com.android.kotlin.multiplatform.library") version "8.11.2"
    `maven-publish`
}
repositories { google(); mavenCentral() }
val pythonCommand = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
fun engineTask(target: String) = tasks.register<Exec>("buildEngine" + target.split('-','_').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }) {
    group = "GDAL runtime"
    // These outputs can arrive from verified CI artifacts. Gradle's first-run
    // stale-output cleanup must not delete them before the producer checks hashes.
    doNotTrackState("The engine producer fingerprints sources and verifies every artifact hash")
    workingDir(rootDir)
    commandLine(pythonCommand, "gdal4k-binary/tools/build-engine.py", target)
    inputs.files(fileTree("ports") { exclude("**/__pycache__/**") })
    inputs.dir("engine")
    inputs.files(fileTree("tools") { include("*.py") })
    inputs.dir("web/vendor")
    inputs.dir(rootProject.file("gdal4k/native"))
    outputs.file(layout.buildDirectory.file("engine/$target/manifest.json"))
    if (target == "web") outputs.dir(layout.buildDirectory.dir("generated/web"))
    else {
        outputs.file(layout.buildDirectory.file("engine/$target/libgdal4k.a"))
        outputs.file(layout.buildDirectory.file("engine/$target/native-linker-options.json"))
        if (target.startsWith("android")) outputs.file(layout.buildDirectory.file("android-runtime/jniLibs/${target.removePrefix("android-")}/libgdal4k.so"))
        else if (!target.startsWith("ios")) outputs.file(layout.buildDirectory.file("jvm-resources/gdal4k/$target/${if (target.startsWith("windows")) "gdal4k.dll" else if (target.startsWith("macos")) "libgdal4k.dylib" else "libgdal4k.so"}"))
    }
}
val engineTargets = listOf("macos-arm64", "macos-amd64", "linux-amd64", "linux-arm64", "windows-amd64", "ios-arm64", "ios-simulator-arm64", "ios-simulator-amd64", "android-arm64-v8a", "web")
val engineTasks = engineTargets.associateWith(::engineTask)
val nativeTargets = mapOf("macosArm64" to "macos-arm64", "macosX64" to "macos-amd64", "linuxX64" to "linux-amd64", "linuxArm64" to "linux-arm64", "mingwX64" to "windows-amd64", "iosArm64" to "ios-arm64", "iosSimulatorArm64" to "ios-simulator-arm64", "iosX64" to "ios-simulator-amd64")
kotlin {
    jvmToolchain(21)
    jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    androidLibrary { namespace = "dev.gdal4k.binary"; compileSdk = 35; minSdk = 28 }
    js { nodejs(); browser() }; wasmJs { nodejs(); browser() }
    macosArm64(); macosX64(); linuxX64(); linuxArm64(); mingwX64()
    iosArm64(); iosSimulatorArm64(); iosX64()
    applyDefaultHierarchyTemplate {
        common {
            group("jni") { withCompilations { it.target.name in listOf("jvm", "android") } }
            group("web") { withJs(); withWasmJs() }
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":gdal4k-runtime"))
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        androidMain.dependencies { api(project(":gdal4k-android-runtime")) }
        for (target in listOf("js", "wasmJs")) {
            getByName("${target}Main").kotlin.srcDir(engineTasks.getValue("web").map { layout.buildDirectory.dir("generated/web/kotlin") })
        }
    }
    targets.withType<KotlinNativeTarget>().configureEach {
        val targetName = name
        val platform = nativeTargets.getValue(targetName)
        compilations["main"].defaultSourceSet.kotlin.srcDir("src/nativeInteropMain/kotlin")
        val prepareDefinition = tasks.register("prepare${name.replaceFirstChar(Char::uppercaseChar)}Definition") {
            dependsOn(engineTasks.getValue(platform))
            val destination = layout.buildDirectory.file("cinterop/$targetName/gdal4k.def")
            outputs.file(destination)
            inputs.file(layout.buildDirectory.file("engine/$platform/native-linker-options.json"))
            doLast {
                val linkedOptions = groovy.json.JsonSlurper().parse(layout.buildDirectory.file("engine/$platform/native-linker-options.json").get().asFile) as List<*>
                // K/N's MinGW driver already supplies static libstdc++; an explicit
                // -lstdc++ would select its DLL import library before that runtime.
                val cxxRuntime = when {
                    platform.startsWith("windows") -> emptyList()
                    platform.startsWith("macos") || platform.startsWith("ios") -> listOf("-lc++")
                    else -> listOf("-lstdc++")
                }
                val systemOptions = (cxxRuntime + linkedOptions.map { it.toString() }).distinct().joinToString(" ")
                destination.get().asFile.apply {
                    parentFile.mkdirs()
                    writeText("headers = gdal4k.h\ncompilerOpts = -DGDAL4K_STATIC\npackage = dev.gdal4k.cinterop\nstaticLibraries = libgdal4k.a\nlibraryPaths = ${layout.buildDirectory.dir("engine/$platform").get().asFile.absolutePath.replace('\\','/')}\nlinkerOpts = $systemOptions\n")
                }
            }
        }
        compilations["main"].cinterops.create("gdal4k") {
            definitionFile.set(layout.buildDirectory.file("cinterop/$targetName/gdal4k.def"))
            includeDirs(rootProject.file("gdal4k/native"))
            tasks.named(interopProcessingTaskName).configure {
                dependsOn(prepareDefinition)
                inputs.file(layout.buildDirectory.file("engine/$platform/libgdal4k.a"))
            }
        }
    }
}
afterEvaluate {
    // Register after KGP's cinterop actions: the archive is inside the KLIB, and
    // the producer-only absolute library search path must not escape into Maven.
    nativeTargets.keys.forEach { targetName ->
        tasks.named("cinteropGdal4k${targetName.replaceFirstChar(Char::uppercaseChar)}").configure {
            doLast {
                outputs.files.files.filter { it.isDirectory }.forEach { directory ->
                    val manifest = directory.resolve("default/manifest")
                    if (manifest.isFile) manifest.writeText(manifest.readLines().filterNot { it.startsWith("libraryPaths=") }.joinToString("\n", postfix = "\n"))
                }
            }
        }
    }
}
// Android's compatibility AAR carries JNI payloads; data is embedded in GDAL and PROJ themselves.

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
