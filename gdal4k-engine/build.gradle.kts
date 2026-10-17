import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins { alias(libs.plugins.kotlin.multiplatform); `maven-publish` }
repositories { mavenCentral() }
val pythonCommand = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
fun registerEngineBuild(target: String) = tasks.register<Exec>("buildEngine" + target.split('-','_').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }) {
    group = "GDAL runtime"
    // These outputs can arrive from verified CI artifacts. Gradle's first-run
    // stale-output cleanup must not delete them before the producer checks hashes.
    doNotTrackState("The engine producer fingerprints sources and verifies every artifact hash")
    workingDir(rootDir)
    commandLine(pythonCommand, "gdal4k-engine/tools/build-engine.py", target)
    environment("GDAL4K_BINARY_VERSION", rootProject.version.toString())
    inputs.files(fileTree("ports") { exclude("**/__pycache__/**") })
    inputs.dir("engine")
    inputs.files(fileTree("tools") { include("*.py") })
    inputs.dir("web")
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
val engineTargets = listOf("macos-arm64", "linux-amd64", "linux-arm64", "windows-amd64", "ios-arm64", "ios-simulator-arm64", "android-arm64-v8a", "web")
engineTargets.forEach(::registerEngineBuild)

val engineBuild = layout.buildDirectory
val nativeTargets = mapOf("macosArm64" to "macos-arm64", "linuxX64" to "linux-amd64", "linuxArm64" to "linux-arm64", "mingwX64" to "windows-amd64", "iosArm64" to "ios-arm64", "iosSimulatorArm64" to "ios-simulator-arm64")
val nativeArtifacts = mapOf(
    "macosArm64" to "macos-arm64", "linuxX64" to "linux-x64-gnu",
    "linuxArm64" to "linux-arm64-gnu", "mingwX64" to "mingw-x64",
    "iosArm64" to "ios-arm64", "iosSimulatorArm64" to "ios-simulator-arm64",
)
fun engineTask(platform: String) = ":gdal4k-engine:buildEngine" + platform.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
kotlin {
    js { nodejs(); browser() }; wasmJs { nodejs(); browser() }
    macosArm64(); linuxX64(); linuxArm64(); mingwX64(); iosArm64(); iosSimulatorArm64()
    targets.withType<KotlinNativeTarget>().configureEach {
        val targetName = name
        mavenPublication { artifactId = "gdal4k-engine-native-${nativeArtifacts.getValue(targetName)}" }
        val platform = nativeTargets.getValue(targetName)
        val prepareDefinition = tasks.register("prepare${name.replaceFirstChar(Char::uppercaseChar)}EngineDefinition") {
            dependsOn(engineTask(platform))
            val destination = layout.buildDirectory.file("cinterop/$targetName/engine.def")
            inputs.file(engineBuild.file("engine/$platform/native-linker-options.json"))
            inputs.file(engineBuild.file("engine/$platform/libgdal4k.a"))
            outputs.file(destination)
            doLast {
                val linked = groovy.json.JsonSlurper().parse(engineBuild.file("engine/$platform/native-linker-options.json").get().asFile) as List<*>
                val cxx = when {
                    platform.startsWith("windows") -> emptyList()
                    platform.startsWith("macos") || platform.startsWith("ios") -> listOf("-lc++")
                    else -> listOf("-lstdc++")
                }
                val options = (cxx + linked.map { it.toString() }).distinct().joinToString(" ")
                destination.get().asFile.apply {
                    parentFile.mkdirs()
                    writeText("headers = gdal4k.h\ncompilerOpts = -DGDAL4K_STATIC\npackage = dev.gdal4k.engine.cinterop\nstaticLibraries = libgdal4k.a\nlibraryPaths = ${engineBuild.dir("engine/$platform").get().asFile.absolutePath.replace('\\','/')}\nlinkerOpts = $options\n")
                }
            }
        }
        compilations["main"].cinterops.create("engine") {
            definitionFile.set(layout.buildDirectory.file("cinterop/$targetName/engine.def"))
            includeDirs(rootProject.file("gdal4k/native"))
            tasks.named(interopProcessingTaskName).configure { dependsOn(prepareDefinition) }
        }
    }
}
afterEvaluate {
    nativeTargets.keys.forEach { target ->
        tasks.named("cinteropEngine${target.replaceFirstChar(Char::uppercaseChar)}").configure {
            doLast {
                outputs.files.files.filter { it.isDirectory }.forEach { directory ->
                    val manifest = directory.resolve("default/manifest")
                    if (manifest.isFile) manifest.writeText(manifest.readLines().filterNot { it.startsWith("libraryPaths=") }.joinToString("\n", postfix="\n"))
                }
            }
        }
    }
}
for (target in listOf("js", "wasmJs")) {
    tasks.named<Jar>("${target}Jar") {
        dependsOn(engineTask("web"))
        from(engineBuild.dir("generated/web/assets"))
    }
    tasks.named("${target}PackageJson") {
        dependsOn(engineTask("web"))
        inputs.dir(engineBuild.dir("generated/web/assets"))
        doLast {
            val jsonFile = outputs.files.singleFile
            val json = groovy.json.JsonSlurper().parse(jsonFile) as MutableMap<String, Any?>
            json["name"] = "@gdal4k/web-engine"
            json["main"] = "index.mjs"
            jsonFile.writeText(groovy.json.JsonOutput.toJson(json))
            copy { from(engineBuild.dir("generated/web/assets")) { exclude("package.json") }; into(jsonFile.parentFile) }
        }
    }
}
