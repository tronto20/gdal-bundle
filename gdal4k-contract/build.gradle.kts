import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("com.android.kotlin.multiplatform.library") version "8.11.2"
}
repositories { mavenCentral() }
val bridgePrefix = providers.gradleProperty("gdal4kBridgePrefix").orElse(rootProject.file("gdal4k/build/bridge").absolutePath)
kotlin {
    androidLibrary { namespace = "dev.gdal4k.contract"; compileSdk = 35; minSdk = 28 }
    jvmToolchain(21)
    jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_17); mainRun { mainClass.set("dev.gdal4k.contract.ContractKt") } }
    js { nodejs(); browser(); binaries.executable() }
    wasmJs { nodejs(); browser(); binaries.executable() }
    macosArm64 { binaries.executable { entryPoint = "dev.gdal4k.contract.main" } }
    linuxX64 { binaries.executable { entryPoint = "dev.gdal4k.contract.main" } }
    linuxArm64 { binaries.executable { entryPoint = "dev.gdal4k.contract.main" } }
    mingwX64 { binaries.executable { entryPoint = "dev.gdal4k.contract.main" } }
    iosSimulatorArm64 { testRuns["test"].deviceId = providers.gradleProperty("iosDevice").orElse("iPhone 16").get() }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":gdal4k"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.all {
            linkerOpts("-L${bridgePrefix.get()}", "-lgdal4k")
            if (konanTarget.family.isAppleFamily) linkerOpts("-rpath", bridgePrefix.get())
            if (konanTarget.family.name == "LINUX") linkerOpts("-Wl,--allow-shlib-undefined")
        }
    }
}
tasks.register<JavaExec>("contractJvm") {
    dependsOn("jvmJar")
    classpath(tasks.named("jvmJar"), configurations["jvmRuntimeClasspath"])
    mainClass.set("dev.gdal4k.contract.ContractKt")
    systemProperty("gdal4k.library", file(bridgePrefix.get()).resolve(System.mapLibraryName("gdal4k")).absolutePath)
}
tasks.register<Copy>("stageJvmRuntime") {
    dependsOn("jvmJar")
    from(tasks.named("jvmJar"), configurations["jvmRuntimeClasspath"])
    into(layout.buildDirectory.dir("jvm-runtime"))
}
