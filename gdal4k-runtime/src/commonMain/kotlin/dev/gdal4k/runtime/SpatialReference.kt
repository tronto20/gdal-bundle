package dev.gdal4k.runtime

/**
 * Shared GDAL/OSR spatial reference handle.
 *
 * This first pass focuses on import/export, basic CRS classification, and the core
 * setters that will be needed by dataset/vector geometry workflows.
 */
interface SpatialReference {
    fun close()

    fun cloneSpatialReference(): SpatialReference

    fun exportToWkt(): String

    fun exportToPrettyWkt(): String

    fun exportToProj4(): String

    fun exportToProjJson(): String

    fun exportToXml(rootElement: String = ""): String

    fun exportToMICoordSys(): String

    fun importFromWkt(wkt: String): Int

    fun importFromProj4(proj4: String): Int

    fun importFromEPSG(epsg: Int): Int

    fun importFromEPSGA(epsga: Int): Int

    fun setFromUserInput(definition: String): Int

    fun setWellKnownGeogCS(name: String): Int

    fun setProjection(name: String): Int

    fun setProjParm(name: String, value: Double): Int

    fun getProjParm(name: String, defaultValue: Double = 0.0): Double

    fun setNormProjParm(name: String, value: Double): Int

    fun getNormProjParm(name: String, defaultValue: Double = 0.0): Double

    fun getName(): String

    fun getCelestialBodyName(): String

    fun isSame(other: SpatialReference): Boolean

    fun isGeographic(): Boolean

    fun isProjected(): Boolean

    fun isGeocentric(): Boolean

    fun isCompound(): Boolean

    fun isVertical(): Boolean

    fun isLocal(): Boolean

    fun isDynamic(): Boolean

    fun hasPointMotionOperation(): Boolean

    fun getCoordinateEpoch(): Double

    fun setCoordinateEpoch(epoch: Double)

    fun getAuthorityName(targetKey: String): String?

    fun getAuthorityCode(targetKey: String): String?

    fun getAttrValue(name: String, child: Int = 0): String?

    fun setAttrValue(name: String, value: String): Int

    fun setAuthority(targetKey: String, authority: String, code: Int): Int

    fun getAngularUnits(): Double

    fun getAngularUnitsName(): String

    fun setAngularUnits(name: String, radiansPerUnit: Double): Int

    fun getLinearUnits(): Double

    fun getLinearUnitsName(): String

    fun setLinearUnits(name: String, metersPerUnit: Double): Int

    fun setLinearUnitsAndUpdateParameters(name: String, metersPerUnit: Double): Int

    fun getAxisName(targetKey: String, index: Int): String?

    fun getAxesCount(): Int

    fun getAxisOrientation(targetKey: String, index: Int): Int

    fun getAxisMappingStrategy(): Int

    fun setAxisMappingStrategy(strategy: Int)

    fun setUTM(zone: Int, north: Int = 1): Int

    fun getUTMZone(): Int

    fun autoIdentifyEPSG(): Int

    fun validate(): Int

    fun morphToESRI(): Int

    fun morphFromESRI(): Int

    fun stripVertical(): Int

    fun cloneGeogCS(): SpatialReference?

    fun convertToOtherProjection(projection: String): SpatialReference?

    fun promoteTo3D(): Int

    fun demoteTo2D(): Int
}

inline fun <T : SpatialReference, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
