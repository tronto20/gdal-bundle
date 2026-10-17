package dev.gdal4k.runtime

/** GDAL 3.12 raster type codes, available without JVM-only SWIG dependencies. */
object GdalDataType {
    const val Unknown = 0
    const val Byte = 1
    const val UInt16 = 2
    const val Int16 = 3
    const val UInt32 = 4
    const val Int32 = 5
    const val Float32 = 6
    const val Float64 = 7
    const val CInt16 = 8
    const val CInt32 = 9
    const val CFloat32 = 10
    const val CFloat64 = 11
    const val UInt64 = 12
    const val Int64 = 13
    const val Int8 = 14
    const val Float16 = 15
    const val CFloat16 = 16
}
