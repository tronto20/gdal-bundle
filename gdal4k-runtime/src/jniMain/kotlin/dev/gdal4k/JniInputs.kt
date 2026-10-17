package dev.gdal4k

import kotlinx.serialization.json.*

/** Accessed only while the JNI transport lock is held. */
internal class JniInputs(private val mount: (JniDirectory) -> String, private val unmount: (String) -> Unit) : AutoCloseable {
    private val sources = mutableMapOf<String, JniInput>()

    fun execute(request: String, attachment: Any?, execute: (String) -> String): String {
        // Avoid parsing raster buffers again when the operation cannot release an input.
        if (attachment == null && !request.contains("\"method\":\"openDataset_String_DatasetOpenOptions\"") &&
            (sources.isEmpty() || !request.contains("\"method\":\"close\""))) return execute(request)
        val root = Json.parseToJsonElement(request).jsonObject
        val args = root.getValue("args").jsonObject
        val datasetOpen = args["type"]?.jsonPrimitive?.content == "GdalRuntime" &&
            args["method"]?.jsonPrimitive?.content == "openDataset_String_DatasetOpenOptions"
        val sourceAttachment = attachment ?: if (datasetOpen)
            jniSourceAttachment(args.getValue("parameters").jsonObject.getValue("source").jsonPrimitive.content) else null
        if (sourceAttachment == null) {
            val response = execute(request)
            if (args["type"]?.jsonPrimitive?.content == "Handle" && args["method"]?.jsonPrimitive?.content == "close") {
                val parsed = Json.parseToJsonElement(response).jsonObject
                // A VRT lease can outlive its source's public handle. Descriptors and
                // directory mounts follow native destruction, including transitive releases.
                val released = parsed["releasedDatasets"]?.jsonArray?.map { it.jsonPrimitive.content }
                    ?: if ("error" !in parsed) listOf(args.getValue("handle").jsonPrimitive.content) else emptyList()
                released.forEach { sources.remove(it)?.close() }
            }
            return response
        }
        require(datasetOpen) {
            "An Android URI attachment can only be used with openDataset"
        }
        val parameters = args.getValue("parameters").jsonObject
        require(parameters.getValue("options").jsonObject["update"]?.jsonPrimitive?.boolean != true) {
            "Android URI inputs are read-only"
        }
        val source = openJniInput(sourceAttachment)
        val input = try {
            source.directory?.let { directory ->
                val prefix = mount(directory)
                JniInput(prefix + source.path) { try { unmount(prefix) } finally { source.close() } }
            } ?: source
        } catch (failure: Throwable) { source.close(); throw failure }
        var owned = true
        try {
            val patched = JsonObject(root + ("args" to JsonObject(args + ("parameters" to
                JsonObject(parameters + ("source" to JsonPrimitive(input.path)))))))
            val response = execute(patched.toString())
            val parsed = Json.parseToJsonElement(response).jsonObject
            if ("error" in parsed) {
                owned = false
                input.close()
            } else {
                val id = parsed.getValue("result").jsonObject.getValue("value").jsonPrimitive.content
                check(id !in sources) { "GDAL reused a live dataset handle" }
                sources[id] = input
                owned = false
            }
            return response
        } finally {
            if (owned) input.close()
        }
    }

    override fun close() {
        var failure: Throwable? = null
        for (source in sources.values) try { source.close() } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        sources.clear()
        failure?.let { throw it }
    }
}
