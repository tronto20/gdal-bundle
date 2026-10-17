package dev.gdal4k

internal actual fun openJniInput(attachment: Any): JniInput =
    error("This input type requires the Android runtime")

internal actual fun jniSourceAttachment(source: String): Any? = null
