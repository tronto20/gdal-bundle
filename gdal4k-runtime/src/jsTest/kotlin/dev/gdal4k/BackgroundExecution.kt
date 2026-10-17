package dev.gdal4k
private fun heartbeat(): dynamic = js("""(() => { let ticks = 0;
const timer = setInterval(() => ticks++, 5);
return { stop() { clearInterval(timer); return ticks; } }; })()""")
private fun stop(timer: dynamic): Int = timer.stop() as Int

internal actual suspend fun verifyBackground(block: suspend () -> Unit) {
    val timer = heartbeat()
    val ticks = try { block(); stop(timer) } catch (error: Throwable) { stop(timer); throw error }
    check(ticks > 0) { "GDAL blocked the JavaScript event loop" }
}
