package dev.gdal4k.gdalbuild

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

abstract class GdalBaseTask @Inject constructor(
    @get:Internal
    protected val execOps: ExecOperations,
    @get:Internal
    protected val layout: ProjectLayout,
) : DefaultTask() {
    @get:Input
    abstract val condaPrefix: Property<String>

    @get:Optional
    @get:Input
    abstract val condaExe: Property<String>

    @Option(option = "conda-prefix", description = "Conda environment prefix")
    fun setCondaPrefixOption(value: String) {
        condaPrefix.set(value)
    }

    @Option(option = "conda-exe", description = "Conda executable path")
    fun setCondaExeOption(value: String) {
        condaExe.set(value)
    }

    protected fun requireCondaPrefix(): String {
        return condaPrefix.orNull?.takeIf { it.isNotBlank() }
            ?: throw GradleException("CONDA_PREFIX or --conda-prefix is required.")
    }

    protected fun resolvePath(value: String): File {
        val file = File(value)
        return if (file.isAbsolute) file else layout.projectDirectory.file(value).asFile
    }

    protected fun resolveCondaExe(prefix: String): String? {
        condaExe.orNull?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        findCondaInPath()?.let { return it }
        return findCondaInPrefix(prefix)
    }
}

abstract class CondaInstallTask @Inject constructor(
    @get:Internal
    protected val execOps: ExecOperations,
    @get:Internal
    protected val layout: ProjectLayout,
) : DefaultTask() {
    @get:Input
    abstract val installerUrl: Property<String>

    @get:Optional
    @get:Input
    abstract val installerSha256: Property<String>

    @get:OutputDirectory
    abstract val installDir: DirectoryProperty

    @Option(option = "installer-url", description = "Conda installer URL")
    fun setInstallerUrlOption(value: String) {
        installerUrl.set(value)
    }

    @Option(option = "installer-sha256", description = "SHA-256 of the installer")
    fun setInstallerSha256Option(value: String) {
        installerSha256.set(value)
    }

    @Option(option = "install-dir", description = "Directory to install conda into")
    fun setInstallDirOption(value: String) {
        installDir.set(resolvePath(value))
    }

    @TaskAction
    fun install() {
        findCondaInPath()?.let { path ->
            logger.lifecycle("Conda found in PATH at $path; skipping local install.")
            return
        }

        val targetDir = installDir.get().asFile
        val condaExePath = findCondaInPrefix(targetDir.absolutePath)
        if (condaExePath != null) {
            logger.lifecycle("Conda already installed at ${targetDir.absolutePath}")
            return
        }

        if (targetDir.exists()) {
            val entries = targetDir.listFiles().orEmpty()
            val safeNames = setOf("conda-installer.sh", "conda-installer.exe", ".DS_Store")
            val unsafeEntries = entries.filterNot { it.name in safeNames }
            if (unsafeEntries.isNotEmpty()) {
                throw GradleException(
                    "Install dir already exists but conda is missing: ${targetDir.absolutePath}. " +
                        "Remove it or use --install-dir to choose a new location.",
                )
            }
            if (!targetDir.deleteRecursively()) {
                throw GradleException("Failed to clean install dir: ${targetDir.absolutePath}")
            }
        }

        targetDir.parentFile?.mkdirs()

        val installerUrlValue = installerUrl.get()
        val isExeInstaller = installerUrlValue.lowercase(java.util.Locale.ROOT).endsWith(".exe")
        if (isWindows() && !isExeInstaller) {
            throw GradleException("Windows installer URL must point to a .exe file.")
        }
        if (!isWindows() && isExeInstaller) {
            throw GradleException("Non-Windows installer URL must point to a .sh file.")
        }

        val installerName = if (isExeInstaller) "conda-installer.exe" else "conda-installer.sh"
        val installerFile = layout.buildDirectory.file("conda-installer/$installerName").get().asFile
        installerFile.parentFile.mkdirs()
        downloadInstaller(installerUrlValue, installerFile)

        installerSha256.orNull?.takeIf { it.isNotBlank() }?.let { expected ->
            val actual = sha256Hex(installerFile)
            if (!actual.equals(expected.trim(), ignoreCase = true)) {
                throw GradleException(
                    "Installer SHA-256 mismatch. expected=$expected actual=$actual",
                )
            }
        }

        execOps.exec {
            if (isExeInstaller) {
                executable = installerFile.absolutePath
                args("/S", "/D=${targetDir.absolutePath}")
            } else {
                executable = "bash"
                args(installerFile.absolutePath, "-b", "-p", targetDir.absolutePath)
            }
            workingDir = layout.projectDirectory.asFile
        }

        if (findCondaInPrefix(targetDir.absolutePath) == null) {
            throw GradleException("Conda install failed. Expected conda in ${targetDir.absolutePath}")
        }
    }

    private fun resolvePath(value: String): File {
        val file = File(value)
        return if (file.isAbsolute) file else layout.projectDirectory.file(value).asFile
    }
}

abstract class GdalBuildTask @Inject constructor(
    execOps: ExecOperations,
    layout: ProjectLayout,
) : GdalBaseTask(execOps, layout) {
    @get:InputFile
    abstract val scriptFile: RegularFileProperty

    @get:Input
    abstract val step: Property<String>

    @get:Input
    abstract val gdalVersion: Property<String>

    @get:Input
    abstract val libkmlVersion: Property<String>

    @get:Input
    abstract val workDir: Property<String>

    @Option(option = "gdal-version", description = "GDAL version to build")
    fun setGdalVersionOption(value: String) {
        gdalVersion.set(value)
    }

    @Option(option = "libkml-version", description = "libkml version to build")
    fun setLibkmlVersionOption(value: String) {
        libkmlVersion.set(value)
    }

    @Option(option = "work-dir", description = "Work directory for GDAL/libkml builds")
    fun setWorkDirOption(value: String) {
        workDir.set(resolvePath(value).absolutePath)
    }

    @TaskAction
    fun runBuild() {
        val prefix = requireCondaPrefix()
        val args = mutableListOf(
            scriptFile.get().asFile.absolutePath,
            "--conda-prefix",
            prefix,
            "--step",
            step.get(),
            "--work-dir",
            workDir.get(),
            "--gdal-version",
            gdalVersion.get(),
            "--libkml-version",
            libkmlVersion.get(),
        )
        resolveCondaExe(prefix)?.let { exe ->
            args.addAll(listOf("--conda-exe", exe))
        }
        execOps.exec {
            executable = "bash"
            args(args)
            workingDir = layout.projectDirectory.asFile
        }
    }
}

abstract class GdalBundleTask @Inject constructor(
    execOps: ExecOperations,
    layout: ProjectLayout,
) : GdalBaseTask(execOps, layout) {
    @get:InputFile
    abstract val scriptFile: RegularFileProperty

    @get:Optional
    @get:Input
    abstract val codesignIdentity: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @Option(option = "codesign-identity", description = "Codesign identity for bundled dylibs")
    fun setCodesignIdentityOption(value: String) {
        codesignIdentity.set(value)
    }

    @Option(option = "output-dir", description = "Output directory for the GDAL bundle")
    fun setOutputDirOption(value: String) {
        outputDir.set(resolvePath(value))
    }

    @TaskAction
    fun runBundle() {
        val args = mutableListOf(
            scriptFile.get().asFile.absolutePath,
            "--conda-prefix",
            requireCondaPrefix(),
            "--output-dir",
            outputDir.get().asFile.absolutePath,
        )
        codesignIdentity.orNull?.takeIf { it.isNotBlank() }?.let { identity ->
            args.addAll(listOf("--codesign-identity", identity))
        }
        execOps.exec {
            executable = "python3"
            args(args)
            workingDir = layout.projectDirectory.asFile
        }
    }
}

abstract class GdalCleanTask @Inject constructor(
    private val layout: ProjectLayout,
) : DefaultTask() {
    @get:Input
    abstract val workDir: Property<String>

    @Option(option = "work-dir", description = "Work directory to delete")
    fun setWorkDirOption(value: String) {
        workDir.set(resolvePath(value).absolutePath)
    }

    @TaskAction
    fun clean() {
        val dir = File(workDir.get())
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    private fun resolvePath(value: String): File {
        val file = File(value)
        return if (file.isAbsolute) file else layout.projectDirectory.file(value).asFile
    }
}
