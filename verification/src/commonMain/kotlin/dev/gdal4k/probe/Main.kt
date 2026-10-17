package dev.gdal4k.probe

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** A GDAL execution probe, not the production gdal4k API. */
expect suspend fun runGdalProbe(): Int

fun main() {
    suspend {
        val result = runGdalProbe()
        check(result == 0) { "GDAL4K_PROBE_FAILED code=$result" }
        println("GDAL4K_PROBE_OK")
    }.startCoroutine(object : Continuation<Unit> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<Unit>) = result.getOrThrow()
    })
}
