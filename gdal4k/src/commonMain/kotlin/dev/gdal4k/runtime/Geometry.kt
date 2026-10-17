package dev.gdal4k.runtime

/**
 * Shared OGR geometry handle.
 *
 * This first pass focuses on geometry construction, export, spatial predicates, and
 * the common collection/point accessors used by feature editing code.
 */
interface Geometry {
    /** Independent CRS copy; remains valid after this geometry closes. */
    suspend fun getSpatialReference(): SpatialReference?
    /** Assigns CRS metadata only. Null removes it; coordinates do not change. */
    suspend fun assignSpatialReference(spatialReference: SpatialReference?)

    /** Planar GEOS overlay in the input coordinate units. Does not reproject or repair inputs.
     * Different explicitly assigned CRSs are rejected. Returns an independent geometry;
     * Empty results may be null or empty geometries; native operation failures throw.
     */
    suspend fun intersection(other: Geometry): Geometry?
    suspend fun union(other: Geometry): Geometry?
    suspend fun difference(other: Geometry): Geometry?

    suspend fun close()

    suspend fun cloneGeometry(): Geometry

    suspend fun getGeometryType(): Int

    suspend fun getGeometryName(): String

    suspend fun getPointCount(): Int

    suspend fun getGeometryCount(): Int

    suspend fun getPoint(index: Int): DoubleArray

    suspend fun getPoints(): List<DoubleArray>

    suspend fun getGeometryRef(index: Int): Geometry?

    suspend fun addPoint(x: Double, y: Double)

    suspend fun addPoint(x: Double, y: Double, z: Double)

    suspend fun addPointM(x: Double, y: Double, m: Double)

    suspend fun addPointZM(x: Double, y: Double, z: Double, m: Double)

    suspend fun addPoint2D(x: Double, y: Double)

    suspend fun addGeometryDirectly(geometry: Geometry): Int

    suspend fun addGeometry(geometry: Geometry): Int

    suspend fun removeGeometry(index: Int): Int

    suspend fun setPoint(index: Int, x: Double, y: Double)

    suspend fun setPoint(index: Int, x: Double, y: Double, z: Double)

    suspend fun setPointM(index: Int, x: Double, y: Double, m: Double)

    suspend fun setPointZM(index: Int, x: Double, y: Double, z: Double, m: Double)

    suspend fun setPoint2D(index: Int, x: Double, y: Double)

    suspend fun swapXY()

    suspend fun exportToWkt(): String

    suspend fun exportToIsoWkt(): String

    suspend fun exportToGml(): String

    suspend fun exportToKml(): String

    suspend fun exportToJson(): String

    suspend fun exportToWkb(): ByteArray

    suspend fun exportToIsoWkb(): ByteArray

    suspend fun getArea(): Double

    suspend fun length(): Double

    suspend fun geodesicLength(): Double

    suspend fun geodesicArea(): Double

    suspend fun distance(other: Geometry): Double

    suspend fun distance3D(other: Geometry): Double

    suspend fun empty()

    suspend fun isEmpty(): Boolean

    suspend fun isValid(): Boolean

    suspend fun isSimple(): Boolean

    suspend fun isRing(): Boolean

    suspend fun intersects(other: Geometry): Boolean

    suspend fun intersectsExact(other: Geometry): Boolean

    suspend fun equal(other: Geometry): Boolean

    suspend fun disjoint(other: Geometry): Boolean

    suspend fun touches(other: Geometry): Boolean

    suspend fun crosses(other: Geometry): Boolean

    suspend fun within(other: Geometry): Boolean

    suspend fun contains(other: Geometry): Boolean

    suspend fun overlaps(other: Geometry): Boolean

    suspend fun getEnvelope(): DoubleArray

    suspend fun getEnvelope3D(): DoubleArray

    suspend fun centroid(): Geometry?

    suspend fun pointOnSurface(): Geometry?

    suspend fun boundary(): Geometry?

    suspend fun convexHull(): Geometry?

    suspend fun buffer(distance: Double): Geometry?

    suspend fun buffer(distance: Double, options: List<String>): Geometry?

    suspend fun simplify(tolerance: Double): Geometry?

    suspend fun simplifyPreserveTopology(tolerance: Double): Geometry?

    suspend fun makeValid(): Geometry?

    suspend fun normalize(): Geometry?

    suspend fun closeRings()

    suspend fun flattenTo2D()

    suspend fun segmentize(maxLength: Double)

    suspend fun wkbSize(): Long

    suspend fun getCoordinateDimension(): Int

    suspend fun setCoordinateDimension(dimension: Int)

    suspend fun is3D(): Boolean

    suspend fun isMeasured(): Boolean

    suspend fun set3D(enabled: Boolean)

    suspend fun setMeasured(enabled: Boolean)

    suspend fun getDimension(): Int

    suspend fun hasCurveGeometry(): Boolean

    suspend fun getLinearGeometry(): Geometry?

    suspend fun getCurveGeometry(): Geometry?
}

suspend inline fun <T : Geometry, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { close() }
    }
}
