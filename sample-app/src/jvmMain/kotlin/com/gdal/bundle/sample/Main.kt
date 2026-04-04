package com.gdal.bundle.sample

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.BorderLayout
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Vector
import javax.swing.JPanel
import org.cef.CefApp
import org.cef.CefClient
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import dev.gdal4k.binary.Gdal4kBinary
import org.gdal.gdal.ProgressCallback
import org.gdal.gdal.TranslateOptions
import org.gdal.gdal.VectorTranslateOptions
import org.gdal.gdal.gdal
import org.gdal.gdalconst.gdalconstConstants
import dev.gdal4k.runtime.use
import kotlin.concurrent.thread
import kotlin.math.roundToInt

private data class GdalEnvironment(
    val gdalDir: File,
    val dataDir: File,
    val projDir: File,
    val pluginsDir: File?,
)

private lateinit var gdalRuntime: dev.gdal4k.runtime.GdalRuntime

private object GdalRuntime {
    @Volatile
    private var initialized = false
    private var environment: GdalEnvironment? = null

    fun ensureInitialized(): GdalEnvironment {
        if (initialized) {
            return requireNotNull(environment)
        }
        synchronized(this) {
            if (!initialized) {
                environment = initialize()
                initialized = true
            }
        }
        return requireNotNull(environment)
    }

    private fun initialize(): GdalEnvironment {
        val gdalDir = locateGdalDir()
        gdalRuntime = Gdal4kBinary.runtime(gdalDir.absolutePath)

        val dataDir = File(gdalDir, "share/gdal")
        val projDir = File(gdalDir, "share/proj")
        val pluginsDir = File(gdalDir, "gdalplugins").takeIf { it.exists() }
        return GdalEnvironment(gdalDir, dataDir, projDir, pluginsDir)
    }

    private fun locateGdalDir(): File {
        val candidates = LinkedHashSet<File>()

        val resourcesDir = System.getProperty("compose.application.resources.dir")
        if (!resourcesDir.isNullOrBlank()) {
            candidates += File(resourcesDir, "gdal")
        }

        val explicitDir = System.getProperty("gdal.bundle.dir") ?: System.getenv("GDAL_BUNDLE_DIR")
        if (!explicitDir.isNullOrBlank()) {
            val explicitFile = File(explicitDir)
            candidates += if (explicitFile.name == "gdal") {
                explicitFile
            } else {
                File(explicitFile, "gdal")
            }
        }


        var base: File? = File(System.getProperty("user.dir"))
        repeat(3) {
            val current = base ?: return@repeat
            base = current.parentFile
        }

        val gdalDir = candidates.firstOrNull { it.exists() && it.isDirectory }
        if (gdalDir != null) {
            return gdalDir
        }

        val checked = candidates.joinToString("\n") { "- ${it.absolutePath}" }
        throw IllegalStateException(
            "GDAL bundle not found. Set gdal.bundle.dir or GDAL_BUNDLE_DIR. Checked paths:\n$checked",
        )
    }
}

private data class JcefBrowserState(
    val app: CefApp,
    val client: CefClient,
    val browser: CefBrowser,
    val panel: JPanel,
) {
    fun dispose() {
        browser.close(true)
        client.dispose()
        app.dispose()
    }
}

private fun configureJcefPaths() {
    val resourcesDir = System.getProperty("compose.application.resources.dir") ?: return
    val jcefDir = File(resourcesDir, "jcef")
    if (!jcefDir.exists()) {
        return
    }

    if (System.getProperty("ALT_JCEF_LIB_DIR").isNullOrBlank()) {
        val libjcef = File(jcefDir, "libjcef.dylib")
        if (libjcef.exists()) {
            System.setProperty("ALT_JCEF_LIB_DIR", jcefDir.absolutePath)
        }
    }

    if (System.getProperty("ALT_CEF_HELPER_APP_DIR").isNullOrBlank()) {
        val helperApp = File(jcefDir, "jcef Helper.app")
        if (helperApp.exists()) {
            System.setProperty("ALT_CEF_HELPER_APP_DIR", jcefDir.absolutePath)
        }
    }

    if (System.getProperty("ALT_CEF_FRAMEWORK_DIR").isNullOrBlank()) {
        val framework = File(jcefDir, "Chromium Embedded Framework.framework")
        if (framework.exists()) {
            System.setProperty("ALT_CEF_FRAMEWORK_DIR", jcefDir.absolutePath)
        }
    }
}

private fun createJcefBrowser(url: String): JcefBrowserState {
    configureJcefPaths()
    val settings = CefSettings().apply {
        windowless_rendering_enabled = false
        no_sandbox = true
    }
    val app = CefApp.getInstance(settings)
    val client = app.createClient()
    val browser = client.createBrowser(url, false, false)
    browser.createImmediately()

    val panel = JPanel(BorderLayout()).apply {
        add(browser.uiComponent, BorderLayout.CENTER)
    }
    return JcefBrowserState(app, client, browser, panel)
}

private fun pickGeoTiffFile(parent: Frame): File? {
    return pickFile(parent, "Select GeoTIFF", FileDialog.LOAD)
}

private fun pickFile(
    parent: Frame,
    title: String,
    mode: Int,
    suggestedName: String? = null,
): File? {
    var selected: File? = null
    val showDialog = Runnable {
        val dialog = FileDialog(parent, title, mode)
        try {
            if (!suggestedName.isNullOrBlank()) {
                dialog.file = suggestedName
            }
            dialog.isVisible = true
            val fileName = dialog.file ?: return@Runnable
            val directory = dialog.directory ?: return@Runnable
            selected = File(directory, fileName)
        } finally {
            dialog.dispose()
        }
    }

    if (EventQueue.isDispatchThread()) {
        showDialog.run()
    } else {
        EventQueue.invokeAndWait(showDialog)
    }

    return selected
}

private fun buildOutputFileName(input: File, suffix: String, extension: String): String {
    val baseName = input.nameWithoutExtension.ifBlank { input.name }
    return "${baseName}_$suffix.$extension"
}

private fun extensionForVectorFormat(format: String): String? {
    return when (format.trim().uppercase()) {
        "GPKG" -> "gpkg"
        "GEOJSON" -> "geojson"
        "ESRI SHAPEFILE", "SHAPEFILE" -> "shp"
        "CSV" -> "csv"
        "KML" -> "kml"
        else -> null
    }
}

private fun parseCreationOptions(rawOptions: String): List<String> {
    if (rawOptions.isBlank()) {
        return emptyList()
    }
    return rawOptions
        .split(Regex("[,;\\s]+"))
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.equals("-co", ignoreCase = true) }
}

private fun loadGdalInfo(file: File): String {
    GdalRuntime.ensureInitialized()

    return gdalRuntime.openDataset(file.absolutePath).use { dataset ->
        gdalRuntime.datasetInfo(dataset).trimEnd().ifBlank { "gdalinfo returned empty output." }
    }
}

private data class DriverSummary(
    val shortName: String,
    val longName: String?,
)

private data class ProgressState(
    val label: String,
    val fraction: Float,
    val message: String?,
)

private fun listGdalDrivers(label: String, capabilityKey: String): String {
    val drivers = collectDriverSummaries(capabilityKey)
    if (drivers.isEmpty()) {
        return "No $label drivers found."
    }
    val builder = StringBuilder()
    builder.append("$label drivers (${drivers.size}):\n")
    for (driver in drivers) {
        builder.append("- ").append(driver.shortName)
        if (!driver.longName.isNullOrBlank() && driver.longName != driver.shortName) {
            builder.append(": ").append(driver.longName)
        }
        builder.append('\n')
    }
    return builder.toString().trimEnd()
}

private fun collectDriverSummaries(capabilityKey: String): List<DriverSummary> {
    val drivers = mutableListOf<DriverSummary>()
    val count = gdal.GetDriverCount()
    for (index in 0 until count) {
        val driver = gdal.GetDriver(index) ?: continue
        val capability = driver.GetMetadataItem(capabilityKey)
        if (!capability.equals("YES", ignoreCase = true)) {
            continue
        }
        drivers += DriverSummary(driver.getShortName(), driver.getLongName())
    }
    return drivers.sortedWith(
        compareBy<DriverSummary> { it.shortName.lowercase() }
            .thenBy { it.longName ?: "" },
    )
}

private fun listSupportedExtensions(): String {
    val extensions = sortedSetOf<String>()
    val count = gdal.GetDriverCount()
    for (index in 0 until count) {
        val driver = gdal.GetDriver(index) ?: continue
        val rawExtensions = driver.GetMetadataItem("DMD_EXTENSIONS")
            ?: driver.GetMetadataItem("DMD_EXTENSION")
        if (rawExtensions.isNullOrBlank()) {
            continue
        }
        rawExtensions
            .split(Regex("[\\s,]+"))
            .map { it.trim().trimStart('.') }
            .filter { it.isNotBlank() }
            .forEach { extensions += it.lowercase() }
    }
    if (extensions.isEmpty()) {
        return "No extensions found."
    }
    val builder = StringBuilder()
    builder.append("Supported extensions (${extensions.size}):\n")
    for (extension in extensions) {
        builder.append("- .").append(extension).append('\n')
    }
    return builder.toString().trimEnd()
}

private fun translateRaster(
    input: File,
    output: File,
    format: String,
    compressionOptions: String,
    progress: ProgressCallback,
): String {
    gdal.ErrorReset()
    if (output.exists()) {
        throw IllegalStateException("Output file already exists: ${output.absolutePath}")
    }
    val dataset = gdal.OpenEx(
        input.absolutePath,
        (gdalconstConstants.OF_RASTER or gdalconstConstants.OF_READONLY or gdalconstConstants.OF_VERBOSE_ERROR)
            .toLong(),
    ) ?: throw IllegalStateException(
        "Unable to open raster: ${input.absolutePath}\n${gdal.GetLastErrorMsg()}",
    )

    val creationOptions = parseCreationOptions(compressionOptions)
    val args = Vector<String>().apply {
        add("-of")
        add(format)
        for (option in creationOptions) {
            add("-co")
            add(option)
        }
    }

    val options = TranslateOptions(args)
    var translated: org.gdal.gdal.Dataset? = null
    return try {
        translated = gdal.Translate(output.absolutePath, dataset, options, progress)
            ?: throw IllegalStateException("gdal_translate failed: ${gdal.GetLastErrorMsg()}")
        buildString {
            appendLine("Raster translate complete.")
            appendLine("Input: ${input.absolutePath}")
            appendLine("Output: ${output.absolutePath}")
            appendLine("Format: $format")
            if (creationOptions.isNotEmpty()) {
                appendLine("Compression: ${creationOptions.joinToString(", ")}")
            } else {
                appendLine("Compression: default")
            }
        }.trimEnd()
    } finally {
        translated?.delete()
        options.delete()
        dataset.delete()
    }
}

private fun translateVector(
    input: File,
    output: File,
    format: String,
    progress: ProgressCallback,
): String {
    gdal.ErrorReset()
    if (output.exists()) {
        throw IllegalStateException("Output file already exists: ${output.absolutePath}")
    }
    val dataset = gdal.OpenEx(
        input.absolutePath,
        (gdalconstConstants.OF_VECTOR or gdalconstConstants.OF_READONLY or gdalconstConstants.OF_VERBOSE_ERROR)
            .toLong(),
    ) ?: throw IllegalStateException(
        "Unable to open vector: ${input.absolutePath}\n${gdal.GetLastErrorMsg()}",
    )

    val args = Vector<String>().apply {
        if (format.isNotBlank()) {
            add("-f")
            add(format)
        }
    }
    val options = VectorTranslateOptions(args)
    var translated: org.gdal.gdal.Dataset? = null
    return try {
        translated = gdal.VectorTranslate(output.absolutePath, dataset, options, progress)
            ?: throw IllegalStateException("ogr2ogr failed: ${gdal.GetLastErrorMsg()}")
        buildString {
            appendLine("Vector translate complete.")
            appendLine("Input: ${input.absolutePath}")
            appendLine("Output: ${output.absolutePath}")
            if (format.isNotBlank()) {
                appendLine("Format: $format")
            }
        }.trimEnd()
    } finally {
        translated?.delete()
        options.delete()
        dataset.delete()
    }
}

fun main() = application {
    Window(
        title = "GDAL GeoTIFF Info",
        state = rememberWindowState(width = 900.dp, height = 700.dp),
        onCloseRequest = ::exitApplication,
    ) {
        App(window as Frame)
    }
}

@Composable
fun App(parent: Frame) {
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf("Select a GeoTIFF to see GDAL info.") }
    var output by remember { mutableStateOf("") }
    var outputTitle by remember { mutableStateOf("Output") }
    var error by remember { mutableStateOf<String?>(null) }
    var gdalPath by remember { mutableStateOf<String?>(null) }
    var rasterCompression by remember { mutableStateOf("COMPRESS=LZW") }
    var vectorFormat by remember { mutableStateOf("GPKG") }
    var progress by remember { mutableStateOf<ProgressState?>(null) }
    var isBusy by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    fun runGdalAction(statusLabel: String, title: String, action: () -> String) {
        output = ""
        error = null
        status = statusLabel
        try {
            val environment = GdalRuntime.ensureInitialized()
            gdalPath = environment.gdalDir.absolutePath
            outputTitle = title
            output = action()
            status = "Done."
        } catch (t: Throwable) {
            t.printStackTrace()
            error = t.message ?: t.javaClass.simpleName
            status = "Failed."
        }
    }
    fun runGdalActionAsync(
        statusLabel: String,
        title: String,
        progressLabel: String,
        action: (ProgressCallback) -> String,
    ) {
        output = ""
        error = null
        status = statusLabel
        outputTitle = title
        isBusy = true
        progress = ProgressState(progressLabel, 0f, null)

        val environment = try {
            GdalRuntime.ensureInitialized()
        } catch (t: Throwable) {
            t.printStackTrace()
            error = t.message ?: t.javaClass.simpleName
            status = "Failed."
            progress = null
            isBusy = false
            return
        }
        gdalPath = environment.gdalDir.absolutePath

        thread(isDaemon = true) {
            val callback = object : ProgressCallback() {
                override fun run(dfComplete: Double, message: String?): Int {
                    val clamped = dfComplete.coerceIn(0.0, 1.0).toFloat()
                    val trimmed = message?.trim()?.takeIf { it.isNotEmpty() }
                    EventQueue.invokeLater {
                        progress = ProgressState(progressLabel, clamped, trimmed)
                    }
                    return 1
                }
            }
            try {
                val result = action(callback)
                EventQueue.invokeLater {
                    output = result
                    status = "Done."
                    progress = null
                    isBusy = false
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                EventQueue.invokeLater {
                    error = t.message ?: t.javaClass.simpleName
                    status = "Failed."
                    progress = null
                    isBusy = false
                }
            } finally {
                callback.delete()
            }
        }
    }

    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
        ) {
            Text("GDAL GeoTIFF Info", style = MaterialTheme.typography.h5)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Bundle: ${gdalPath ?: "not loaded"}", style = MaterialTheme.typography.body2)
            Spacer(modifier = Modifier.height(12.dp))
            Text("Browser (JCEF)", style = MaterialTheme.typography.subtitle1)
            Spacer(modifier = Modifier.height(6.dp))

            val jcefResult = remember { runCatching { createJcefBrowser("https://www.google.com") } }
            val jcefState = jcefResult.getOrNull()
            val jcefError = jcefResult.exceptionOrNull()?.message
            if (jcefState != null) {
                DisposableEffect(jcefState) {
                    onDispose {
                        jcefState.dispose()
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .border(1.dp, Color(0xFFDDDDDD)),
                ) {
                    SwingPanel(
                        factory = { jcefState.panel },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                Text(
                    text = "JCEF failed to initialize: ${jcefError ?: "Unknown error"}",
                    color = Color(0xFFB00020),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    enabled = !isBusy,
                    onClick = {
                        val file = pickGeoTiffFile(parent) ?: return@Button
                        selectedFile = file
                        runGdalAction(
                            statusLabel = "Running gdalinfo...",
                            title = "Output (gdalinfo)",
                        ) {
                            loadGdalInfo(file)
                        }
                    },
                ) {
                    Text("Choose GeoTIFF")
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(selectedFile?.absolutePath ?: "No file selected", modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Lists:", style = MaterialTheme.typography.body2)
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    enabled = !isBusy,
                    onClick = {
                        runGdalAction(
                            statusLabel = "Listing raster drivers...",
                            title = "Output (Raster drivers)",
                        ) {
                            listGdalDrivers("Raster", "DCAP_RASTER")
                        }
                    },
                ) {
                    Text("Raster Drivers")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    enabled = !isBusy,
                    onClick = {
                        runGdalAction(
                            statusLabel = "Listing vector drivers...",
                            title = "Output (Vector drivers)",
                        ) {
                            listGdalDrivers("Vector", "DCAP_VECTOR")
                        }
                    },
                ) {
                    Text("Vector Drivers")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    enabled = !isBusy,
                    onClick = {
                        runGdalAction(
                            statusLabel = "Listing supported extensions...",
                            title = "Output (Supported extensions)",
                        ) {
                            listSupportedExtensions()
                        }
                    },
                ) {
                    Text("Extensions")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Translate", style = MaterialTheme.typography.subtitle1)
            Spacer(modifier = Modifier.height(6.dp))
            Text("Raster (gdal_translate)", style = MaterialTheme.typography.body2)
            Spacer(modifier = Modifier.height(6.dp))
            TextField(
                value = rasterCompression,
                onValueChange = { rasterCompression = it },
                label = { Text("Compression options for COG/GTiff (comma/space-separated)") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBusy,
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    enabled = !isBusy,
                    onClick = {
                        val input = pickFile(parent, "Select raster input", FileDialog.LOAD) ?: return@Button
                        val output = pickFile(
                            parent,
                            "Save COG output",
                            FileDialog.SAVE,
                            buildOutputFileName(input, "cog", "tif"),
                        ) ?: return@Button
                        runGdalActionAsync(
                            statusLabel = "Translating raster to COG...",
                            title = "Output (Raster translate: COG)",
                            progressLabel = "Raster translate (COG)",
                        ) {
                            translateRaster(input, output, "COG", rasterCompression, it)
                        }
                    },
                ) {
                    Text("Raster -> COG")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    enabled = !isBusy,
                    onClick = {
                        val input = pickFile(parent, "Select raster input", FileDialog.LOAD) ?: return@Button
                        val output = pickFile(
                            parent,
                            "Save GTiff output",
                            FileDialog.SAVE,
                            buildOutputFileName(input, "gtiff", "tif"),
                        ) ?: return@Button
                        runGdalActionAsync(
                            statusLabel = "Translating raster to GTiff...",
                            title = "Output (Raster translate: GTiff)",
                            progressLabel = "Raster translate (GTiff)",
                        ) {
                            translateRaster(input, output, "GTiff", rasterCompression, it)
                        }
                    },
                ) {
                    Text("Raster -> GTiff")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text("Vector (ogr2ogr)", style = MaterialTheme.typography.body2)
            Spacer(modifier = Modifier.height(6.dp))
            TextField(
                value = vectorFormat,
                onValueChange = { vectorFormat = it },
                label = { Text("Output format (e.g. GPKG, GeoJSON)") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBusy,
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    enabled = !isBusy,
                    onClick = {
                        val input = pickFile(parent, "Select vector input", FileDialog.LOAD) ?: return@Button
                        val normalizedFormat = vectorFormat.trim().ifBlank { "GPKG" }
                        if (normalizedFormat != vectorFormat) {
                            vectorFormat = normalizedFormat
                        }
                        val extension = extensionForVectorFormat(normalizedFormat) ?: "gpkg"
                        val output = pickFile(
                            parent,
                            "Save vector output",
                            FileDialog.SAVE,
                            buildOutputFileName(input, "vector", extension),
                        ) ?: return@Button
                        runGdalActionAsync(
                            statusLabel = "Translating vector...",
                            title = "Output (Vector translate)",
                            progressLabel = "Vector translate",
                        ) {
                            translateVector(input, output, normalizedFormat, it)
                        }
                    },
                ) {
                    Text("Vector Translate")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Status: $status")
            if (error != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Error: $error", color = Color(0xFFB00020))
            }
            if (progress != null) {
                val current = progress!!
                val percent = (current.fraction * 100).roundToInt()
                Spacer(modifier = Modifier.height(8.dp))
                Text("${current.label}: $percent%")
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = current.fraction,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!current.message.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(current.message)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(outputTitle, style = MaterialTheme.typography.subtitle1)
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .border(1.dp, Color(0xFFDDDDDD))
                    .padding(12.dp)
                    .verticalScroll(scrollState),
            ) {
                val content = if (output.isNotBlank()) output else "No output yet."
                SelectionContainer {
                    Text(content)
                }
            }
        }
    }
}
