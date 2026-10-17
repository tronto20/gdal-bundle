package dev.gdal4k

import org.w3c.files.File

private fun inputSet(files: dynamic, urls: String?): dynamic = js("({kind: files ? 'files' : 'urls', files: files, urls: urls ? JSON.parse(urls) : null})")

internal actual fun createWebInputSet(files: List<File>?, urls: String?): Any = inputSet(files?.toTypedArray(), urls)
