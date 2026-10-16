import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins { kotlin("multiplatform") version "2.4.21" }

val probePrefix = providers.gradleProperty("probePrefix").orElse(layout.projectDirectory.dir("build/native").asFile.absolutePath)
kotlin {
    jvm { mainRun { mainClass.set("dev.gdal4k.probe.MainKt") } }
    js { nodejs(); browser(); binaries.executable() }
    wasmJs { nodejs(); browser(); binaries.executable() }
    macosArm64 { binaries.executable { entryPoint = "dev.gdal4k.probe.main" } }
    linuxX64 { binaries.executable { entryPoint = "dev.gdal4k.probe.main" } }
    linuxArm64 { binaries.executable { entryPoint = "dev.gdal4k.probe.main" } }
    mingwX64 { binaries.executable { entryPoint = "dev.gdal4k.probe.main" } }
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops.create("probe") {
            definitionFile.set(layout.projectDirectory.file("src/nativeInterop/cinterop/probe.def"))
            includeDirs("native")
        }
        binaries.all { linkerOpts("-L${probePrefix.get()}", "-lgdal4k_probe") }
        if (name.startsWith("linux")) binaries.all { linkerOpts("-Wl,--allow-shlib-undefined") }
    }
}

tasks.register<JavaExec>("probeJvm") {
    dependsOn("jvmJar")
    classpath(tasks.named("jvmJar"), configurations.getByName("jvmRuntimeClasspath"))
    mainClass.set("dev.gdal4k.probe.MainKt")
    systemProperty("gdal4k.probe.library", file(probePrefix.get()).resolve(System.mapLibraryName("gdal4k_probe")).absolutePath)
}

tasks.register<Copy>("stageJvmRuntime") {
    dependsOn("jvmJar")
    from(tasks.named("jvmJar"), configurations.getByName("jvmRuntimeClasspath"))
    into(layout.buildDirectory.dir("jvm-runtime"))
}
