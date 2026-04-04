package dev.gdal4k.runtime

/**
 * Shared OGR geometry handle.
 *
 * This first pass focuses on geometry construction, export, spatial predicates, and
 * the common collection/point accessors used by feature editing code.
 */
interface Geometry {
    fun close()

    fun cloneGeometry(): Geometry

    fun getGeometryType(): Int

    fun getGeometryName(): String

    fun getPointCount(): Int

    fun getGeometryCount(): Int

    fun getPoint(index: Int): DoubleArray

    fun getPoints(): List<DoubleArray>

    fun getGeometryRef(index: Int): Geometry?

    fun addPoint(x: Double, y: Double)

    fun addPoint(x: Double, y: Double, z: Double)

    fun addPointM(x: Double, y: Double, m: Double)

    fun addPointZM(x: Double, y: Double, z: Double, m: Double)

    fun addPoint2D(x: Double, y: Double)

    fun addGeometryDirectly(geometry: Geometry): Int

    fun addGeometry(geometry: Geometry): Int

    fun removeGeometry(index: Int): Int

    fun setPoint(index: Int, x: Double, y: Double)

    fun setPoint(index: Int, x: Double, y: Double, z: Double)

    fun setPointM(index: Int, x: Double, y: Double, m: Double)

    fun setPointZM(index: Int, x: Double, y: Double, z: Double, m: Double)

    fun setPoint2D(index: Int, x: Double, y: Double)

    fun swapXY()

    fun exportToWkt(): String

    fun exportToIsoWkt(): String

    fun exportToGml(): String

    fun exportToKml(): String

    fun exportToJson(): String

    fun exportToWkb(): ByteArray

    fun exportToIsoWkb(): ByteArray

    fun getArea(): Double

    fun length(): Double

    fun geodesicLength(): Double

    fun geodesicArea(): Double

    fun distance(other: Geometry): Double

    fun distance3D(other: Geometry): Double

    fun empty()

    fun isEmpty(): Boolean

    fun isValid(): Boolean

    fun isSimple(): Boolean

    fun isRing(): Boolean

    fun intersects(other: Geometry): Boolean

    fun intersectsExact(other: Geometry): Boolean

    fun equal(other: Geometry): Boolean

    fun disjoint(other: Geometry): Boolean

    fun touches(other: Geometry): Boolean

    fun crosses(other: Geometry): Boolean

    fun within(other: Geometry): Boolean

    fun contains(other: Geometry): Boolean

    fun overlaps(other: Geometry): Boolean

    fun getEnvelope(): DoubleArray

    fun getEnvelope3D(): DoubleArray

    fun centroid(): Geometry?

    fun pointOnSurface(): Geometry?

    fun boundary(): Geometry?

    fun convexHull(): Geometry?

    fun buffer(distance: Double): Geometry?

    fun buffer(distance: Double, options: List<String>): Geometry?

    fun simplify(tolerance: Double): Geometry?

    fun simplifyPreserveTopology(tolerance: Double): Geometry?

    fun makeValid(): Geometry?

    fun normalize(): Geometry?

    fun closeRings()

    fun flattenTo2D()

    fun segmentize(maxLength: Double)

    fun wkbSize(): Long

    fun getCoordinateDimension(): Int

    fun setCoordinateDimension(dimension: Int)

    fun is3D(): Boolean

    fun isMeasured(): Boolean

    fun set3D(enabled: Boolean)

    fun setMeasured(enabled: Boolean)

    fun getDimension(): Int

    fun hasCurveGeometry(): Boolean

    fun getLinearGeometry(): Geometry?

    fun getCurveGeometry(): Geometry?
}

inline fun <T : Geometry, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
