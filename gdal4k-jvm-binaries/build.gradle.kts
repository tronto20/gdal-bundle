// JVM JNI JARs are optional classifiers of the gdal4k-engine root publication.
plugins { base }

data class JvmBinary(val task: String, val classifier: String, val platform: String, val library: String)
val binaries = listOf(
    JvmBinary("macosArm64", "macos-arm64", "macos-arm64", "libgdal4k.dylib"),
    JvmBinary("linuxX64Gnu", "linux-x64-gnu", "linux-amd64", "libgdal4k.so"),
    JvmBinary("linuxArm64Gnu", "linux-arm64-gnu", "linux-arm64", "libgdal4k.so"),
    JvmBinary("windowsX64", "windows-x64", "windows-amd64", "gdal4k.dll"),
)
binaries.forEach { binary ->
    val payload = project(":gdal4k-engine").layout.buildDirectory.file("jvm-resources/gdal4k/${binary.platform}/${binary.library}")
    val jar = tasks.register<Jar>("${binary.task}Jar") {
        dependsOn(":gdal4k-engine:buildEngine" + binary.platform.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) })
        archiveBaseName.set("gdal4k-engine")
        archiveClassifier.set(binary.classifier)
        from(payload) { into("gdal4k/${binary.platform}") }
        doFirst { require(payload.get().asFile.isFile) { "Missing JVM payload: ${binary.platform}" } }
    }
    // Production apps select a classifier explicitly; tests select their host task.
    configurations.create("${binary.task}RuntimeElements") {
        isCanBeResolved = false
        isCanBeConsumed = true
        outgoing.artifact(jar)
    }
}
