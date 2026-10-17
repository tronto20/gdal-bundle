package dev.gdal4k.binary

import dev.gdal4k.*
import dev.gdal4k.runtime.GdalRuntime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bundled GDAL initialization. No GDAL installation or runtime path is required. */
object Gdal4kBinary {
    private val mutex = Mutex()
    @kotlin.concurrent.Volatile private var instance: GdalRuntime? = null
    private var engine: RuntimeEngine? = null

    suspend fun prepare(bundleDir: String? = null): Unit {
        currentCoroutineContext().ensureActive()
        mutex.withLock {
            if (instance == null) initialize(bundleDir)
        }
    }

    /** Optional release of the shared engine while the application is still alive. */
    suspend fun close(): Unit = withContext(NonCancellable) {
        mutex.withLock { try { engine?.close() } finally { instance = null; engine = null } }
        Unit
    }

    /** Lazily initializes once. Initialization, acquisition and close share the same lock. */
    suspend fun runtime(): GdalRuntime {
        currentCoroutineContext().ensureActive()
        return mutex.withLock { instance ?: initialize(null) }
    }

    // Called only while holding mutex. Publish a completely initialized runtime.
    private suspend fun initialize(bundleDir: String?): GdalRuntime = withContext(NonCancellable) {
        val transport = createTransport(bundledOptions(bundleDir))
        val created = RuntimeEngine(transport)
        val runtime = BundledGdalRuntime(created)
        engine = created
        instance = runtime
        runtime
    }
}

internal expect fun bundledOptions(bundleDir: String?): GdalOptions
