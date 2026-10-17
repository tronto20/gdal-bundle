import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.api.publish.maven.MavenPublication

// Extend the common root with Android routing and an artifact-free JVM choice.
// JVM binaries remain explicit classifiers; existing Native/web payloads are kept.
val engineVariantTool = rootProject.file("gdal4k-engine/tools/engine_variants.py")
val engineVariantPython = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
tasks.withType<GenerateModuleMetadata>().configureEach {
    inputs.file(engineVariantTool)
    doLast {
        project.exec {
            commandLine(engineVariantPython, engineVariantTool.absolutePath, outputFile.get().asFile.absolutePath, "--module", (publication.get() as MavenPublication).artifactId)
        }
    }
}
