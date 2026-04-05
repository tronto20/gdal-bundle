package dev.gdal4k.sample.cli

import dev.gdal4k.binary.Gdal4kBinary
import kotlin.system.exitProcess

private const val DEBUG_PROPERTY = "sample-cli.debug"

suspend fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] == "-h" || args[0] == "--help") {
        printUsage()
        if (args.isEmpty()) {
            exitProcess(1)
        }
        return
    }

    when (args[0]) {
        "info" -> runInfo(args.drop(1))
        "smoke" -> runSmoke(args.drop(1))
        else -> {
            System.err.println("Unknown command: ${args[0]}")
            printUsage()
            exitProcess(1)
        }
    }
}

private suspend fun runInfo(args: List<String>) {
    if (args.isEmpty()) {
        System.err.println("Missing dataset path.")
        printUsage()
        exitProcess(1)
    }

    val datasetPath = args.first()
    val infoArgs = args.drop(1)

    debug("preparing GDAL runtime")
    Gdal4kBinary.prepare()
    val runtime = Gdal4kBinary.runtime()

    debug("opening dataset $datasetPath")
    val output = DatasetInfoService(runtime).describe(datasetPath, infoArgs)
    debug("dataset info completed")
    println(output)
}

private suspend fun runSmoke(args: List<String>) {
    if (args.isNotEmpty()) {
        System.err.println("sample-cli smoke does not accept arguments.")
        printUsage()
        exitProcess(1)
    }

    debug("preparing GDAL runtime")
    Gdal4kBinary.prepare()
    val runtime = Gdal4kBinary.runtime()

    debug("running surface smoke checks")
    val output = SurfaceSmokeService(runtime).run()
    debug("surface smoke completed")
    println(output)
}

private fun debug(message: String) {
    if (java.lang.Boolean.getBoolean(DEBUG_PROPERTY)) {
        System.err.println("[sample-cli] $message")
    }
}

private fun printUsage() {
    println(
        """
        Usage:
          sample-cli info <dataset-path> [gdal-info-args...]
          sample-cli smoke

        Examples:
          sample-cli info ./example.tif
          sample-cli info ./example.tif --json
          sample-cli smoke

        The GDAL runtime bundle is packaged inside the gdal4k-binary dependency.
        You can still override it with -Dgdal.bundle.dir or GDAL_BUNDLE_DIR if needed.
        """.trimIndent(),
    )
}
