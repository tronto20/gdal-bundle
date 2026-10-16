package dev.gdal4k.probe

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private external fun gdal4kBeginProbe(success: (Int) -> Unit, failure: (String) -> Unit)

actual suspend fun runGdalProbe(): Int = suspendCoroutine { continuation ->
    gdal4kBeginProbe(
        { continuation.resume(it) },
        { continuation.resumeWithException(IllegalStateException(it)) },
    )
}
