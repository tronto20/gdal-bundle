package dev.gdal4k.gdalbuild

import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

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
            error(
                "Unsupported OS/arch: ${System.getProperty("os.name")} / ${System.getProperty("os.arch")}. " +
                    "Use --installer-url to override.",
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
    } catch (_: Exception) {
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

fun downloadInstaller(url: String, output: File) {
    URL(url).openStream().use { input ->
        Files.copy(input, output.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

fun sha256Hex(file: File): String {
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
