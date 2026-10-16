package dev.gdal4k

import dev.gdal4k.binary.Gdal4kBinary
import dev.gdal4k.runtime.GdalRuntime

/** Default entry point for the bundled GDAL runtime on every supported platform. */
object Gdal4k {
    /** Optional warm-up. [runtime] also initializes the engine on first use. */
    suspend fun prepare() = Gdal4kBinary.prepare()

    /** Returns the shared runtime, initializing it asynchronously if necessary. */
    suspend fun runtime(): GdalRuntime = Gdal4kBinary.runtime()

    /**
     * Optionally releases the shared engine/Worker and invalidates its handles.
     * Not required as an application exit hook. Close datasets with use to flush writes.
     * The next [runtime] call creates a new engine.
     */
    suspend fun close() = Gdal4kBinary.close()
}
