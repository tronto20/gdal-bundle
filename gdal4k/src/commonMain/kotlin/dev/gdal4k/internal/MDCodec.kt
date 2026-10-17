package dev.gdal4k.internal

import dev.gdal4k.runtime.*
import kotlinx.serialization.json.*

internal fun RuntimeHandle.encodeMDType(type: MDDataType): JsonElement = buildJsonObject {
    when (type) {
        is MDDataType.Numeric -> { put("kind", "numeric"); put("type", type.dataType) }
        is MDDataType.Text -> { put("kind", "text"); put("maxLength", encode(type.maxLength)) }
        is MDDataType.Compound -> {
            put("kind", "compound"); put("name", encode(type.name))
            put("components", JsonArray(type.components.map { c -> buildJsonObject {
                put("name", encode(c.name)); put("type", encodeMDType(c.dataType))
            } }))
        }
    }
}
internal fun decodeMDType(element: JsonElement): MDDataType = element.jsonObject.let { t ->
    when (t.getValue("kind").jsonPrimitive.content) {
        "numeric" -> MDDataType.Numeric(t.getValue("type").jsonPrimitive.int)
        "text" -> MDDataType.Text(t.getValue("maxLength").jsonPrimitive.content.toLong())
        "compound" -> MDDataType.Compound(t.getValue("name").jsonPrimitive.content, t.getValue("components").jsonArray.map { c ->
            c.jsonObject.let { MDComponent(it.getValue("name").jsonPrimitive.content, decodeMDType(it.getValue("type"))) }
        })
        else -> error("Unknown GDAL multidimensional type")
    }
}
internal fun RuntimeHandle.encodeMDValue(value: MDValue): JsonElement = buildJsonObject {
    when (value) {
        is MDValue.Numeric -> { put("kind", "numeric"); put("real", encode(value.real)); put("imaginary", encode(value.imaginary)) }
        is MDValue.Text -> { put("kind", "text"); put("text", encode(value.value)) }
        is MDValue.Record -> { put("kind", "record"); put("fields", JsonArray(value.fields.map(::encodeMDValue))) }
    }
}
internal fun decodeMDValue(element: JsonElement): MDValue = element.jsonObject.let { v ->
    when (v.getValue("kind").jsonPrimitive.content) {
        "numeric" -> MDValue.Numeric(v.getValue("real").jsonPrimitive.content, v["imaginary"]?.jsonPrimitive?.contentOrNull)
        "text" -> MDValue.Text(v.getValue("text").jsonPrimitive.contentOrNull)
        "record" -> MDValue.Record(v.getValue("fields").jsonArray.map(::decodeMDValue))
        else -> error("Unknown GDAL multidimensional value")
    }
}
internal fun decodeCodedValue(element: JsonElement): CodedFieldValue = element.jsonObject.let {
    CodedFieldValue(it.getValue("code").jsonPrimitive.content, it.getValue("label").jsonPrimitive.contentOrNull)
}
internal fun decodeDomainBound(element: JsonElement): FieldDomainBound = element.jsonObject.let {
    FieldDomainBound(it.getValue("value").jsonPrimitive.content, it.getValue("inclusive").jsonPrimitive.boolean)
}
