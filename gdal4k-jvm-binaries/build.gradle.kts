// Resource-only JVM publications. Nothing depends on these by default.
plugins { base; `maven-publish` }

data class JvmBinary(val publication: String, val suffix: String, val platform: String, val library: String)
val binaries = listOf(
    JvmBinary("macosArm64", "macos-arm64", "macos-arm64", "libgdal4k.dylib"),
    JvmBinary("linuxX64Gnu", "linux-x64-gnu", "linux-amd64", "libgdal4k.so"),
    JvmBinary("linuxArm64Gnu", "linux-arm64-gnu", "linux-arm64", "libgdal4k.so"),
    JvmBinary("windowsX64", "windows-x64", "windows-amd64", "gdal4k.dll"),
)
binaries.forEach { binary ->
    val artifactName = "gdal4k-engine-jvm-${binary.suffix}"
    val payload = project(":gdal4k-binary").layout.buildDirectory.file("jvm-resources/gdal4k/${binary.platform}/${binary.library}")
    val jar = tasks.register<Jar>("${binary.publication}Jar") {
        val engineTask = binary.platform.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        dependsOn(":gdal4k-binary:buildEngine$engineTask")
        archiveBaseName.set(artifactName)
        from(payload) { into("gdal4k/${binary.platform}") }
        doFirst { require(payload.get().asFile.isFile) { "Missing JVM payload: ${binary.platform}" } }
    }
    // Used only by this repository's tests; production code has no host default.
    configurations.create("${binary.publication}RuntimeElements") {
        isCanBeResolved = false
        isCanBeConsumed = true
        outgoing.artifact(jar)
    }
    publishing.publications.create<MavenPublication>(binary.publication) {
        artifactId = artifactName
        artifact(jar)
        pom {
            name.set(artifactName)
            description.set("GDAL and JPEG XL JNI payload for ${binary.suffix}; select explicitly alongside gdal4k")
        }
    }
}
