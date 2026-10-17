import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins { alias(libs.plugins.kotlin.multiplatform); `maven-publish` }
repositories { mavenCentral() }
val engineBuild = project(":gdal4k-binary").layout.buildDirectory
val nativeTargets = mapOf("macosArm64" to "macos-arm64", "linuxX64" to "linux-amd64", "linuxArm64" to "linux-arm64", "mingwX64" to "windows-amd64", "iosArm64" to "ios-arm64", "iosSimulatorArm64" to "ios-simulator-arm64")
val nativeArtifacts = mapOf(
    "macosArm64" to "macos-arm64", "linuxX64" to "linux-x64-gnu",
    "linuxArm64" to "linux-arm64-gnu", "mingwX64" to "mingw-x64",
    "iosArm64" to "ios-arm64", "iosSimulatorArm64" to "ios-simulator-arm64",
)
fun engineTask(platform: String) = ":gdal4k-binary:buildEngine" + platform.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
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
