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
            if (instance == null) {
                withContext(NonCancellable) {
                    val transport = createTransport(bundledOptions(bundleDir))
                    val created = RuntimeEngine(transport)
                    engine = created
                    instance = BundledGdalRuntime(created)
                }
            }
        }
    }

    /** Finish the current operation and release every owned dataset, handle, and Worker. */
    suspend fun close(): Unit = withContext(NonCancellable) {
        mutex.withLock { try { engine?.close() } finally { instance = null; engine = null } }
        Unit
    }

    fun runtime(): GdalRuntime = instance ?: error("Call Gdal4kBinary.prepare() first")
}

internal expect fun bundledOptions(bundleDir: String?): GdalOptions
