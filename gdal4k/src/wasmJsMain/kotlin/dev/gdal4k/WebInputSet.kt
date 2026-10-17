@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package dev.gdal4k

import org.w3c.files.File
import kotlin.js.*

@JsFun("() => []") private external fun inputArray(): JsAny
@JsFun("(array, file) => { array.push(file); }") private external fun appendFile(array: JsAny, file: File)
@JsFun("(files, urls) => ({kind: files ? 'files' : 'urls', files, urls: urls ? JSON.parse(urls) : null})")
private external fun inputSet(files: JsAny?, urls: String?): JsAny

internal actual fun createWebInputSet(files: List<File>?, urls: String?): Any =
    inputSet(files?.let { selected -> inputArray().also { array -> selected.forEach { appendFile(array, it) } } }, urls)
