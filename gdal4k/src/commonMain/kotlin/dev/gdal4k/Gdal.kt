package dev.gdal4k

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64

/** Runtime locations. Native consumers link libgdal4k; JVM/Android may load an absolute library path.
 * Browser/Node hosts supply webBridgeUrl (gdal4k-web.mjs) and webAssetsUrl (gdal3.js assets).
 * Driver availability is reported by drivers(), and depends on the installed GDAL build.
 */
data class GdalOptions(
    val libraryPath: String? = null,
    val projData: String? = null,
    val gdalData: String? = null,
    val webBridgeUrl: String? = null,
    val webAssetsUrl: String? = null,
)

/** One file in a GDAL dataset; names are basenames, including extensions. */
data class GdalFile(val name: String, val bytes: ByteArray) {
    init { validName(name) }
}

/** Supports multi-file formats (for example .shp/.dbf/.shx). All files share one private directory. */
data class GdalFiles(val main: String, val files: List<GdalFile>) {
    init {
        validName(main)
        require(files.isNotEmpty() && files.map { it.name }.distinct().size == files.size) { "File names must be unique" }
        require(files.any { it.name == main }) { "Missing main file: $main" }
    }
    constructor(file: GdalFile) : this(file.name, listOf(file))
    fun mainFile(): GdalFile = files.first { it.name == main }
}

data class GdalPoint(val x: Double, val y: Double, val z: Double = 0.0)
data class RasterWindow(val x: Int, val y: Int, val width: Int, val height: Int) {
    init { require(x >= 0 && y >= 0 && width > 0 && height > 0) }
}
data class GdalDatasetInfo(
    val driver: String,
    val width: Int,
    val height: Int,
    val bandCount: Int,
    val layerCount: Int,
)
data class GdalDriver(val name: String, val longName: String, val raster: Boolean, val vector: Boolean)
class GdalException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A GDAL engine instance. Calls are serialized, including close; datasets belong to their session.
 * Always close the session or use [use]. Closing a session closes all its datasets.
 */
class GdalSession internal constructor(private val transport: GdalTransport) {
    private val mutex = Mutex()
    private var closed = false
    private val datasets = mutableSetOf<GdalDataset>()

    suspend fun drivers(): List<GdalDriver> = locked {
        request("drivers").jsonArray.map { value ->
            val d = value.jsonObject
            GdalDriver(d.text("name"), d.text("longName"), d["raster"]!!.jsonPrimitive.boolean, d["vector"]!!.jsonPrimitive.boolean)
        }
    }
    suspend fun open(file: GdalFile): GdalDataset = open(GdalFiles(file))
    suspend fun open(files: GdalFiles, openOptions: List<String> = emptyList()): GdalDataset = locked {
        val result = request("open", buildJsonObject {
            put("main", files.main); put("files", files.toJson()); put("options", strings(openOptions))
        }).jsonObject
        val info = GdalDatasetInfo(result.text("driver"), result.number("width"), result.number("height"), result.number("bands"), result.number("layers"))
        GdalDataset(this, result.text("id"), info).also { datasets += it }
    }

    /** Coordinates use traditional GIS axis order: longitude/easting first, latitude/northing second. */
    suspend fun transform(source: String, target: String, points: List<GdalPoint>): List<GdalPoint> = locked {
        require(points.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }) { "Coordinates must be finite" }
        if (points.isEmpty()) return@locked emptyList()
        request("transform", buildJsonObject {
            put("source", source); put("target", target)
            put("points", JsonArray(points.map { JsonArray(listOf(JsonPrimitive(it.x), JsonPrimitive(it.y), JsonPrimitive(it.z))) }))
        }).jsonArray.map { p -> p.jsonArray.let { GdalPoint(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double, it[2].jsonPrimitive.double) } }
    }

    suspend fun close() = mutex.withLock {
        if (!closed) {
            transport.close()
            closed = true
            datasets.forEach { it.closed = true }
            datasets.clear()
        }
    }

    internal suspend fun datasetCall(dataset: GdalDataset, operation: String, args: JsonObject = buildJsonObject {}): JsonElement = locked {
        check(dataset in datasets && !dataset.closed) { "Dataset is closed" }
        request(operation, JsonObject(args + ("id" to JsonPrimitive(dataset.id))))
    }
    internal suspend fun closeDataset(dataset: GdalDataset) = mutex.withLock {
        if (!closed && !dataset.closed) {
            request("close", buildJsonObject { put("id", dataset.id) })
            datasets -= dataset
            dataset.closed = true
        }
    }
    private suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock {
        check(!closed) { "GDAL session is closed" }
        block()
    }
    private suspend fun request(operation: String, args: JsonObject = buildJsonObject {}): JsonElement {
        val response = try {
            Json.parseToJsonElement(transport.call(buildJsonObject { put("op", operation); put("args", args) }.toString())).jsonObject
        } catch (e: Exception) { throw GdalException("GDAL $operation failed: ${e.message}", e) }
        response["error"]?.let { throw GdalException("GDAL $operation failed: ${it.jsonPrimitive.content}") }
        return response["result"] ?: throw GdalException("GDAL $operation returned no result")
    }
}

class GdalDataset internal constructor(
    private val session: GdalSession,
    internal val id: String,
    val info: GdalDatasetInfo,
) {
    internal var closed = false
    /** GDAL JSON output: raster gdalinfo or vector ogrinfo (-so -al). */
    suspend fun info(arguments: List<String> = emptyList()): JsonObject = session.datasetCall(this, "info", buildJsonObject { put("options", strings(arguments)) }).jsonObject
    suspend fun translate(driver: String = "GTiff", name: String = "output.tif", arguments: List<String> = emptyList()): GdalFiles = convert("translate", driver, name, arguments)
    suspend fun warp(target: String, driver: String = "GTiff", name: String = "warped.tif", arguments: List<String> = emptyList()): GdalFiles = convert("warp", driver, name, listOf("-t_srs", target) + arguments)
    suspend fun translateVector(driver: String = "GeoJSON", name: String = "output.geojson", arguments: List<String> = emptyList()): GdalFiles = convert("vector", driver, name, arguments)

    /** Reads one band, in row-major order, without applying scale/offset or replacing NoData.
     * Uses GDAL's XYZ conversion for identical semantics across native and web engines.
     */
    suspend fun readRaster(band: Int = 1, window: RasterWindow? = null): DoubleArray {
        require(band in 1..info.bandCount) { "Band indices are 1-based" }
        window?.let { require(it.x.toLong() + it.width <= info.width && it.y.toLong() + it.height <= info.height) { "Window is outside the raster" } }
        val args = mutableListOf("-b", band.toString(), "-co", "SIGNIFICANT_DIGITS=17")
        window?.let { args += listOf("-srcwin", "${it.x}", "${it.y}", "${it.width}", "${it.height}") }
        val output = translate("XYZ", "pixels.xyz", args).mainFile().bytes.decodeToString()
        val values = output.lineSequence().filter { it.isNotBlank() }.map { line ->
            val number = line.trim().substringAfterLast(' ').substringAfterLast('\t')
            when (number.lowercase()) { "nan" -> Double.NaN; "inf", "+inf" -> Double.POSITIVE_INFINITY; "-inf" -> Double.NEGATIVE_INFINITY; else -> number.toDouble() }
        }.toList().toDoubleArray()
        val expected = window?.let { it.width.toLong() * it.height } ?: (info.width.toLong() * info.height)
        check(values.size.toLong() == expected) { "GDAL returned ${values.size} pixels; expected $expected" }
        return values
    }
    suspend fun close() = session.closeDataset(this)
    private suspend fun convert(op: String, driver: String, name: String, arguments: List<String>): GdalFiles {
        validName(name)
        return session.datasetCall(this, op, buildJsonObject { put("driver", driver); put("name", name); put("options", strings(arguments)) }).jsonObject.toFiles()
    }
}

suspend inline fun <T> GdalSession.use(block: (GdalSession) -> T): T = try { block(this) } finally { withContext(NonCancellable) { close() } }
suspend inline fun <T> GdalDataset.use(block: (GdalDataset) -> T): T = try { block(this) } finally { withContext(NonCancellable) { close() } }

/** Shared factory for JVM, Android, Kotlin/Native, JS and wasmJs. WASI is intentionally excluded. */
object Gdal {
    suspend fun create(options: GdalOptions = GdalOptions()): GdalSession = GdalSession(createTransport(options))
}
internal interface GdalTransport {
    suspend fun call(request: String): String
    suspend fun close()
}
internal expect suspend fun createTransport(options: GdalOptions): GdalTransport
internal fun validName(name: String) {
    require(name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' || it == ':' }) { "Expected a file basename: $name" }
}
internal fun strings(values: List<String>): JsonArray {
    require(values.none { '\u0000' in it }) { "GDAL arguments cannot contain NUL" }
    return JsonArray(values.map(::JsonPrimitive))
}
internal fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
internal fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.int
internal fun GdalFiles.toJson() = JsonArray(files.map { buildJsonObject { put("name", it.name); put("data", Base64.encode(it.bytes)) } })
internal fun JsonObject.toFiles() = GdalFiles(text("main"), getValue("files").jsonArray.map { value -> value.jsonObject.let { GdalFile(it.text("name"), Base64.decode(it.text("data"))) } })
