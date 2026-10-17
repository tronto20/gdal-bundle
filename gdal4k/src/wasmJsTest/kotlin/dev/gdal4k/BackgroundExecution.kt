@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package dev.gdal4k
import kotlin.js.*
@JsFun("""() => { let ticks = 0;
const timer = setInterval(() => ticks++, 5);
return { stop() { clearInterval(timer); return ticks; } }; }""")
private external fun heartbeat(): JsAny
@JsFun("timer => timer.stop()")
private external fun stop(timer: JsAny): Int

internal actual suspend fun verifyBackground(block: suspend () -> Unit) {
    val timer = heartbeat()
    val ticks = try { block(); stop(timer) } catch (error: Throwable) { stop(timer); throw error }
    check(ticks > 0) { "GDAL blocked the JavaScript event loop" }
}
