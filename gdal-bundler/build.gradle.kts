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
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject

plugins {
    base
}

fun defaultCondaInstallerUrl(): String {
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)
    val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
    val baseUrl = "https://github.com/conda-forge/miniforge/releases/latest/download/"

    return when {
        os.contains("mac") && (arch == "arm64" || arch == "aarch64") ->
            "${baseUrl}Miniforge3-MacOSX-arm64.sh"
        os.contains("mac") && (arch == "x86_64" || arch == "amd64") ->
            "${baseUrl}Miniforge3-MacOSX-x86_64.sh"
        os.contains("linux") && (arch == "arm64" || arch == "aarch64") ->
            "${baseUrl}Miniforge3-Linux-aarch64.sh"
        os.contains("linux") && (arch == "x86_64" || arch == "amd64") ->
            "${baseUrl}Miniforge3-Linux-x86_64.sh"
        os.contains("win") && (arch == "arm64" || arch == "aarch64") ->
            "${baseUrl}Miniforge3-Windows-arm64.exe"
        os.contains("win") && (arch == "x86_64" || arch == "amd64") ->
            "${baseUrl}Miniforge3-Windows-x86_64.exe"
        else ->
            throw GradleException(
                "Unsupported OS/arch: ${System.getProperty("os.name")} / ${System.getProperty("os.arch")}. " +
                    "Use --installer-url to override."
            )
    }
}

fun isWindows(): Boolean {
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)
    return os.contains("win")
}

fun condaExecutableNames(): List<String> {
    return if (isWindows()) {
        listOf("conda.exe", "conda.bat", "conda.cmd")
    } else {
        listOf("conda")
    }
}

fun isExecutable(file: File): Boolean {
    return if (isWindows()) file.isFile else file.isFile && file.canExecute()
}

fun findCondaInPath(): String? {
    val pathValue = System.getenv("PATH") ?: return null
    return pathValue.split(File.pathSeparator)
        .asSequence()
        .flatMap { dir ->
            condaExecutableNames().asSequence().map { name -> File(dir, name) }
        }
        .firstOrNull { isExecutable(it) }
        ?.absolutePath
}

fun resolveCondaBase(condaExe: String): String? {
    return try {
        val command = if (isWindows() && (condaExe.endsWith(".bat", true) || condaExe.endsWith(".cmd", true))) {
            listOf("cmd.exe", "/c", "\"$condaExe\" info --base")
        } else {
            listOf(condaExe, "info", "--base")
        }
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        val lastLine = output.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
        if (exitCode == 0 && !lastLine.isNullOrBlank()) {
            lastLine
        } else {
            inferCondaBaseFromExe(condaExe)
        }
    } catch (e: Exception) {
        inferCondaBaseFromExe(condaExe)
    }
}

fun inferCondaBaseFromExe(condaExe: String): String? {
    val exeFile = File(condaExe)
    val parent = exeFile.parentFile ?: return null
    val parentName = parent.name.lowercase(Locale.ROOT)
    val base = parent.parentFile ?: return null
    return when (parentName) {
        "bin", "scripts", "condabin" -> base.absolutePath
        else -> null
    }
}

fun findCondaInPrefix(prefix: String): String? {
    val base = File(prefix)
    val candidates = if (isWindows()) {
        listOf(
            File(base, "Scripts/conda.exe"),
            File(base, "Scripts/conda.bat"),
            File(base, "Scripts/conda.cmd"),
            File(base, "condabin/conda.bat"),
            File(base, "condabin/conda.cmd"),
        )
    } else {
        listOf(File(base, "bin/conda"))
    }
    return candidates.firstOrNull { it.isFile }?.absolutePath
}

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
                        "Remove it or use --install-dir to choose a new location."
                )
            }
            if (!targetDir.deleteRecursively()) {
                throw GradleException("Failed to clean install dir: ${targetDir.absolutePath}")
            }
        }

        targetDir.parentFile?.mkdirs()

        val installerUrlValue = installerUrl.get()
        val isExeInstaller = installerUrlValue.lowercase(Locale.ROOT).endsWith(".exe")
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
                    "Installer SHA-256 mismatch. expected=$expected actual=$actual"
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

    private fun downloadInstaller(url: String, output: File) {
        URL(url).openStream().use { input ->
            Files.copy(input, output.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath()).use { input ->
            val buffer = ByteArray(8192)
            var read = input.read(buffer)
            while (read > 0) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
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

val buildScriptFile = layout.projectDirectory.file("scripts/build_and_bundle_gdal_macos.sh")
val bundleScriptFile = layout.projectDirectory.file("scripts/bundle_gdal_macos.py")
val defaultWorkDir = layout.buildDirectory.dir("gdal-work").map { it.asFile.absolutePath }
val defaultOutputDir = layout.buildDirectory.dir("gdal-bundle")
val defaultCondaInstallDir = layout.buildDirectory.dir("conda")
val defaultCondaInstallerUrlProvider = providers.provider { defaultCondaInstallerUrl() }
val defaultCondaPrefixProvider = providers.provider {
    findCondaInPath()?.let { resolveCondaBase(it) }
        ?: defaultCondaInstallDir.get().asFile.absolutePath
}

tasks.withType<GdalBaseTask>().configureEach {
    condaPrefix.convention(
        providers.environmentVariable("CONDA_PREFIX").orElse(defaultCondaPrefixProvider)
    )
    condaExe.convention(providers.environmentVariable("CONDA_EXE"))
}

tasks.withType<GdalBuildTask>().configureEach {
    scriptFile.set(buildScriptFile)
    workDir.convention(defaultWorkDir)
    gdalVersion.convention("3.12.2")
    libkmlVersion.convention("1.3.0")
}

tasks.withType<GdalBundleTask>().configureEach {
    scriptFile.set(bundleScriptFile)
    outputDir.convention(defaultOutputDir)
    codesignIdentity.convention(providers.environmentVariable("CODESIGN_IDENTITY"))
}

val cleanGdalWorkDir by tasks.registering(GdalCleanTask::class) {
    group = "gdal"
    description = "Delete the GDAL build work directory."
    workDir.convention(defaultWorkDir)
}

val installConda by tasks.registering(CondaInstallTask::class) {
    group = "gdal"
    description = "Install Miniforge (conda) into the local build directory."
    installDir.convention(defaultCondaInstallDir)
    installerUrl.convention(defaultCondaInstallerUrlProvider)
}

val installGdalDeps by tasks.registering(GdalBuildTask::class) {
    group = "gdal"
    description = "Install GDAL build dependencies into the conda prefix."
    step.set("deps")
}

val buildLibkmlMacos by tasks.registering(GdalBuildTask::class) {
    group = "gdal"
    description = "Build libkml from source for macOS."
    step.set("libkml")
    dependsOn(installGdalDeps)
}

val buildGdalMacos by tasks.registering(GdalBuildTask::class) {
    group = "gdal"
    description = "Build GDAL from source for macOS with JNI."
    step.set("gdal")
    dependsOn(buildLibkmlMacos)
}

val bundleGdalMacos by tasks.registering(GdalBundleTask::class) {
    group = "gdal"
    description = "Bundle GDAL dylibs and data from a conda environment for macOS."
}

val buildAndBundleGdalMacos by tasks.registering {
    group = "gdal"
    description = "Build GDAL + libkml from source and bundle dylibs for macOS."
    dependsOn(buildGdalMacos, bundleGdalMacos)
}

bundleGdalMacos.configure {
    mustRunAfter(buildGdalMacos)
}
