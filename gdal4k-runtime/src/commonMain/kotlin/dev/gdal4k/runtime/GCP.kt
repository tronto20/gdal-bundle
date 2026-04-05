package dev.gdal4k.runtime

/**
 * Shared GDAL Ground Control Point handle.
 */
interface GCP {
    fun close()

    fun getGCPX(): Double

    fun setGCPX(value: Double)

    fun getGCPY(): Double

    fun setGCPY(value: Double)

    fun getGCPZ(): Double

    fun setGCPZ(value: Double)

    fun getGCPPixel(): Double

    fun setGCPPixel(value: Double)

    fun getGCPLine(): Double

    fun setGCPLine(value: Double)

    fun getInfo(): String

    fun setInfo(info: String)

    fun getId(): String

    fun setId(id: String)
}

inline fun <T : GCP, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
