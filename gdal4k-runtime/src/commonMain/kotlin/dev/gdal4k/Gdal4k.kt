package dev.gdal4k

import dev.gdal4k.internal.BundledGdalRuntime
import dev.gdal4k.internal.RuntimeEngine
import dev.gdal4k.internal.bundledOptions
import dev.gdal4k.runtime.GdalRuntime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bundled GDAL initialization. No GDAL installation or runtime path is required. */
object Gdal4k {
    private val mutex = Mutex()
    @kotlin.concurrent.Volatile private var instance: GdalRuntime? = null
    private var engine: RuntimeEngine? = null

    /** Optional warm-up. [runtime] also initializes the engine on first use. */
    suspend fun prepare() {
        currentCoroutineContext().ensureActive()
        mutex.withLock {
            if (instance == null) initialize()
        }
    }

    /**
     * Releases the shared engine/Worker and invalidates its handles.
     * Optional as an application exit hook; close datasets with use to flush writes.
     * The next [runtime] call creates a new engine.
     */
    suspend fun close() {
        withContext(NonCancellable) {
            mutex.withLock { try { engine?.close() } finally { instance = null; engine = null } }
        }
    }

    /** Lazily initializes once. Initialization, acquisition and close share the same lock. */
    suspend fun runtime(): GdalRuntime {
        currentCoroutineContext().ensureActive()
        return mutex.withLock { instance ?: initialize() }
    }

    // Called only while holding mutex. Publish a completely initialized runtime.
    private suspend fun initialize(): GdalRuntime = withContext(NonCancellable) {
        val transport = createTransport(bundledOptions(null))
        val created = RuntimeEngine(transport)
        val runtime = BundledGdalRuntime(created)
        engine = created
        instance = runtime
        runtime
    }
}
