package dev.gdal4k

import kotlinx.coroutines.*

internal actual suspend fun verifyBackground(block: suspend () -> Unit) = withContext(Dispatchers.Default.limitedParallelism(1)) {
    var ticks = 0
    val heartbeat = launch(start = CoroutineStart.UNDISPATCHED) { while (isActive) { delay(5); ticks++ } }
    try {
        block()
        check(ticks > 0) { "GDAL blocked the calling execution context" }
    } finally { heartbeat.cancelAndJoin() }
}
