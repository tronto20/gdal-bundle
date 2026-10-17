import dev.tronto.buildtools.WriteToolEnvironment

plugins {
    id("dev.tronto.buildtools.cmake")
    id("dev.tronto.buildtools.ninja")
    id("dev.tronto.buildtools.emscripten")
    id("dev.tronto.buildtools.vcpkg")
}

// Exercise relocation, including spaces, without configuring the GDAL/Kotlin producer.
cmakeTool { directory.set(layout.projectDirectory.dir(".gradle/cmake store")) }
ninjaTool { directory.set(layout.projectDirectory.dir(".gradle/ninja store")) }
emscriptenTool { directory.set(layout.projectDirectory.dir(".gradle/emsdk store")) }
vcpkgTool { directory.set(layout.projectDirectory.dir(".gradle/vcpkg store")) }

val nativeEnvironment by tasks.registering(WriteToolEnvironment::class) {
    dependsOn("prepareCmake", "prepareNinja", "prepareVcpkg")
    variables.put("CMAKE_COMMAND", cmakeTool.executable.map { it.asFile.absolutePath })
    variables.put("NINJA_COMMAND", ninjaTool.executable.map { it.asFile.absolutePath })
    variables.put("BUILD_TOOLS_PYTHON", vcpkgTool.pythonExecutable)
    variables.put("VCPKG_ROOT", vcpkgTool.installationDirectory.map { it.asFile.absolutePath })
    variables.put("PATH_PREFIX", cmakeTool.installationDirectory.zip(cmakeTool.binPath) { dir, bin -> dir.dir(bin).asFile.absolutePath }
        .zip(ninjaTool.installationDirectory) { cmake, ninja -> cmake + java.io.File.pathSeparator + ninja.asFile.absolutePath })
    environmentFile.set(layout.buildDirectory.file("environment/native.json"))
}
tasks.register<WriteToolEnvironment>("webEnvironment") {
    dependsOn(nativeEnvironment, "prepareEmscripten")
    variables.set(nativeEnvironment.flatMap { it.variables })
    variables.put("EMSDK", emscriptenTool.installationDirectory.map { it.asFile.absolutePath })
    variables.put("EM_CONFIG", emscriptenTool.installationDirectory.file(".emscripten").map { it.asFile.absolutePath })
    variables.put("EM_CACHE", emscriptenTool.installationDirectory.dir("upstream/emscripten/cache").map { it.asFile.absolutePath })
    variables.put("PATH_PREFIX", nativeEnvironment.flatMap { it.variables }.zip(emscriptenTool.installationDirectory) { vars, sdk ->
        vars.getValue("PATH_PREFIX") + java.io.File.pathSeparator + sdk.dir("upstream/emscripten").asFile.absolutePath + java.io.File.pathSeparator + sdk.dir("upstream/bin").asFile.absolutePath
    })
    environmentFile.set(layout.buildDirectory.file("environment/web.json"))
}
