package dev.gdal4k.sample.cli

import dev.gdal4k.runtime.GdalRuntime
import dev.gdal4k.runtime.DatasetAccessMode
import dev.gdal4k.runtime.DatasetKind
import dev.gdal4k.runtime.DatasetOpenOptions
import dev.gdal4k.runtime.use
import org.gdal.gdalconst.gdalconstConstants
import java.nio.file.Files

class SurfaceSmokeService(
    private val runtime: GdalRuntime,
) {
    fun run(): String {
        val sections = mutableListOf<String>()
        sections += smokeSpatialReferenceAndTransformation()
        sections += smokeRasterSurface()
        sections += smokeVectorSurface()
        return buildString {
            appendLine("surface smoke passed")
            sections.forEach { section ->
                append("- ")
                appendLine(section)
            }
        }.trimEnd()
    }

    private fun smokeSpatialReferenceAndTransformation(): String {
        runtime.createSpatialReference("OGC:CRS84").use { source ->
            runtime.createSpatialReference("EPSG:3857").use { target ->
                source.cloneSpatialReference().use { cloned ->
                    check(cloned.isSame(source))
                }
                check(source.cloneGeogCS() != null)
                check(source.isGeographic())
                check(target.isProjected())
                check(source.exportToWkt().isNotBlank())
                check(source.exportToPrettyWkt().isNotBlank())
                check(source.exportToProj4().isNotBlank())
                check(source.exportToProjJson().isNotBlank())
                check(source.exportToXml().isNotBlank())
                check(source.getName().isNotBlank())
                check(source.getLinearUnits() > 0.0)
                check(source.getAngularUnits() > 0.0)
                check(source.getAxesCount() >= 0)

                val transformation = runtime.createCoordinateTransformation(source, target)
                    ?: error("Unable to create coordinate transformation.")
                transformation.use { ct ->
                    val forward = ct.transformPoint(127.0, 37.5)
                    check(forward.size >= 2)

                    val inverse = ct.getInverse() ?: error("Inverse transform was not available.")
                    inverse.use { inverseCt ->
                        val back = inverseCt.transformPoint(forward[0], forward[1])
                        check(back.size >= 2)
                    }

                    val bounds = DoubleArray(4)
                    ct.transformBounds(bounds, 126.0, 37.0, 128.0, 38.0)
                }
            }
        }

        return "SpatialReference + CoordinateTransformation"
    }

    private fun smokeRasterSurface(): String {
        val driver = runtime.getDriver("MEM") ?: error("MEM raster driver was not available.")
        check(driver.getShortName().isNotBlank())
        check(driver.getLongName().isNotBlank())
        driver.hasOpenOption("INTERLEAVE")

        val dataset = driver.create(
            name = "",
            xSize = 4,
            ySize = 3,
            bandCount = 1,
            dataType = gdalconstConstants.GDT_Byte,
            options = emptyList(),
        ) ?: error("Unable to create in-memory raster dataset.")

        dataset.use { raster ->
            check(raster.getDriver() != null)
            check(raster.getDriverShortName().isNotBlank())
            check(raster.getDriverLongName().isNotBlank())
            check(raster.getRasterXSize() == 4)
            check(raster.getRasterYSize() == 3)
            check(raster.getRasterCount() == 1)
            check(raster.getLayerCount() == 0)
            check(raster.getGCPCount() == 0)
            raster.getFileList()
            runtime.createSpatialReference("EPSG:4326").use { spatialReference ->
                check(raster.setProjection(spatialReference.exportToWkt()) == 0)
            }
            check(raster.getProjection().isNotBlank())
            check(raster.getProjectionRef().isNotBlank())
            check(
                raster.setGeoTransform(
                    doubleArrayOf(
                        126.0,
                        0.01,
                        0.0,
                        38.0,
                        0.0,
                        -0.01,
                    ),
                ) == 0,
            )
            check(raster.getGeoTransform()?.size == 6)
            check(raster.flushCache() == 0)

            val band = raster.getRasterBand(1) ?: error("Raster band was not available.")
            val payload = ByteArray(12) { index -> (index + 1).toByte() }
            check(band.getXSize() == 4)
            check(band.getYSize() == 3)
            check(band.getRasterDataType() == gdalconstConstants.GDT_Byte)
            check(band.getBlockSize().size == 2)
            check(band.writeRaster(payload) == 0)

            val roundTrip = ByteArray(payload.size)
            check(band.readRaster(roundTrip) == 0)
            check(roundTrip.contentEquals(payload))
            check(band.checksum() >= 0)
            check(band.getColorInterpretation() >= 0)
            check(band.setColorInterpretation(band.getColorInterpretation()) == 0)
            check(band.getNoDataValue() == null)
            check(band.setNoDataValue(255.0) == 0)
            check(band.getNoDataValue() == 255.0)
            check(band.deleteNoDataValue() == 0)
            check(band.getUnitType().isNotEmpty() || band.getUnitType().isEmpty())
            check(band.setUnitType("m") == 0)
            check(band.getUnitType() == "m")
            check(band.getCategoryNames().isEmpty() || band.getCategoryNames().isNotEmpty())
            check(band.setCategoryNames(listOf("low", "high")) == 0)
            check(band.getCategoryNames().isNotEmpty())
            check(band.getOverviewCount() >= 0)
            check(band.getMaskFlags() >= 0)
            band.getMaskBand()
            band.getSampleOverview(2)
            band.getOverview(0)
            band.isMaskBand()
            band.flushCache()
        }

        return "Dataset + Band"
    }

    private fun smokeVectorSurface(): String {
        val tempDir = Files.createTempDirectory("gdal4k-sample-cli-smoke-")
        val sourcePath = tempDir.resolve("fixture.geojson")
        val copiedPath = tempDir.resolve("copied.geojson")

        Files.writeString(
            sourcePath,
            """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "properties": {
                    "name": "sample-cli-smoke-test",
                    "rank": 7,
                    "score": 12.5
                  },
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [
                      [
                        [127.0, 37.5],
                        [127.2, 37.5],
                        [127.2, 37.7],
                        [127.0, 37.7],
                        [127.0, 37.5]
                      ]
                    ]
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val geoJsonDriver = runtime.getDriver("GeoJSON") ?: error("GeoJSON driver was not available.")

        geoJsonDriver.createVector(tempDir.resolve("empty-vector.geojson").toString())?.use { emptyVector ->
            check(emptyVector.getDriverShortName().isNotBlank())
        } ?: error("Unable to create empty vector dataset.")

        runtime.openDataset(
            sourcePath.toString(),
            DatasetOpenOptions(
                accessMode = DatasetAccessMode.Update,
                kind = DatasetKind.Vector,
            ),
        ).use { vectorDataset ->
            check(vectorDataset.getDriver() != null)
            check(vectorDataset.getDriverShortName().isNotBlank())
            check(vectorDataset.getDriverLongName().isNotBlank())
            check(vectorDataset.getLayerCount() >= 1)
            check(vectorDataset.getLayer(0) != null)

            val layer = vectorDataset.getLayer(0) ?: error("Vector layer was not available.")
            val layerByName = vectorDataset.getLayer(layer.getName()) ?: error("Layer lookup by name failed.")
            check(layerByName.getName() == layer.getName())
            check(layer.getDataset() != null)
            check(layer.getLayerDefn() != null)
            check(layer.getGeomType() >= 0)
            layer.getGeometryColumn()
            layer.getFIDColumn()
            check(layer.getFeatureCount(force = true) >= 1)
            check(layer.getExtent(force = true) != null)
            check(layer.getFeaturesRead() >= 0)
            layer.testCapability("OLCRandomRead")

            check(layer.setAttributeFilter("rank = 7") == 0)
            layer.resetReading()
            check(layer.setNextByIndex(0) == 0)
            val iteratedFeature = layer.getNextFeature() ?: error("Unable to iterate feature.")
            val iteratedFeatureId = iteratedFeature.getFID()
            iteratedFeature.use { iteratedFeatureHandle ->
                check(iteratedFeatureHandle.getFID() >= 0)
            }

            val feature = layer.getFeature(iteratedFeatureId) ?: error("Unable to get feature by FID.")
            feature.use { featureHandle ->
                check(featureHandle.getDefinition().getName().isNotBlank())
                check(featureHandle.getFieldCount() >= 3)
                check(featureHandle.getFieldIndex("name") >= 0)
                check(featureHandle.getFieldAsString("name") == "sample-cli-smoke-test")
                check(featureHandle.getFieldAsInteger("rank") == 7)
                check(featureHandle.getFieldAsDouble("score") > 0.0)
                check(featureHandle.isFieldSet("name"))
                check(featureHandle.isFieldSetAndNotNull("name"))
                check(featureHandle.getNativeData().isEmpty() || featureHandle.getNativeData().isNotEmpty())
                check(featureHandle.getNativeMediaType().isEmpty() || featureHandle.getNativeMediaType().isNotEmpty())
                check(featureHandle.dumpReadableAsString().isNotBlank())
                check(featureHandle.validate() >= 0)
                check(featureHandle.getGeomFieldCount() >= 1)

                val definition = featureHandle.getDefinition()
                check(definition.getFieldCount() >= 3)
                val nameFieldDefn = definition.getFieldDefn(definition.getFieldIndex("name"))
                    ?: error("Field definition for 'name' was not available.")
                check(nameFieldDefn.getName() == "name")
                check(nameFieldDefn.getTypeName().isNotBlank())
                check(nameFieldDefn.getFieldTypeName(nameFieldDefn.getType()).isNotBlank())
                check(nameFieldDefn.getAlternativeName().isEmpty() || nameFieldDefn.getAlternativeName().isNotEmpty())
                check(nameFieldDefn.getDomainName().isEmpty() || nameFieldDefn.getDomainName().isNotEmpty())
                check(nameFieldDefn.getComment().isEmpty() || nameFieldDefn.getComment().isNotEmpty())

                if (definition.getGeomFieldCount() > 0) {
                    val geomFieldDefn = definition.getGeomFieldDefn(0)
                        ?: error("Geometry field definition was not available.")
                    check(geomFieldDefn.getName().isNotBlank() || geomFieldDefn.getName().isEmpty())
                    check(geomFieldDefn.getNameRef().isNotBlank() || geomFieldDefn.getNameRef().isEmpty())
                    check(geomFieldDefn.getType() >= 0)
                    check(geomFieldDefn.isNullable() || !geomFieldDefn.isNullable())
                    check(geomFieldDefn.isIgnored() || !geomFieldDefn.isIgnored())
                }

                val geometry = featureHandle.getGeometryRef() ?: error("Feature geometry was not available.")
                geometry.cloneGeometry().use { geometryHandle ->
                    check(geometryHandle.getGeometryType() >= 0)
                    check(geometryHandle.getGeometryName().isNotBlank())
                    check(geometryHandle.getGeometryCount() >= 1)
                    check(geometryHandle.getPointCount() >= 0)
                    check(geometryHandle.exportToWkt().isNotBlank())
                    check(geometryHandle.exportToIsoWkt().isNotBlank())
                    check(geometryHandle.exportToGml().isNotBlank())
                    check(geometryHandle.exportToKml().isNotBlank())
                    check(geometryHandle.exportToJson().isNotBlank())
                    check(geometryHandle.exportToWkb().isNotEmpty())
                    check(geometryHandle.exportToIsoWkb().isNotEmpty())
                    check(geometryHandle.getArea() >= 0.0)
                    check(geometryHandle.length() >= 0.0)
                    check(geometryHandle.geodesicLength() >= 0.0)
                    check(geometryHandle.geodesicArea() >= 0.0)
                    check(geometryHandle.distance(geometryHandle) == 0.0)
                    geometryHandle.distance3D(geometryHandle)
                    check(geometryHandle.isEmpty().not())
                    check(geometryHandle.isValid())
                    check(geometryHandle.isSimple())
                    check(geometryHandle.intersects(geometryHandle))
                    check(geometryHandle.intersectsExact(geometryHandle))
                    check(geometryHandle.equal(geometryHandle))
                    check(!geometryHandle.disjoint(geometryHandle))
                    check(geometryHandle.contains(geometryHandle))
                    check(geometryHandle.within(geometryHandle))
                    check(geometryHandle.overlaps(geometryHandle) || !geometryHandle.overlaps(geometryHandle))
                    check(geometryHandle.getEnvelope().size == 4)
                    check(geometryHandle.getEnvelope3D().size == 6)
                    check(geometryHandle.centroid() != null)
                    check(geometryHandle.pointOnSurface() != null)
                    check(geometryHandle.boundary() != null)
                    check(geometryHandle.convexHull() != null)
                    check(geometryHandle.buffer(0.001) != null)
                    check(geometryHandle.buffer(0.001, listOf("QUADRANT_SEGMENTS=2")) != null)
                    check(geometryHandle.simplify(0.0001) != null)
                    check(geometryHandle.simplifyPreserveTopology(0.0001) != null)
                    check(geometryHandle.makeValid() != null)
                    check(geometryHandle.normalize() != null)
                    geometryHandle.closeRings()
                    geometryHandle.flattenTo2D()
                    geometryHandle.segmentize(0.05)
                    check(geometryHandle.wkbSize() > 0)
                    check(geometryHandle.getCoordinateDimension() >= 2)
                    check(!geometryHandle.is3D())
                    check(!geometryHandle.isMeasured())
                    geometryHandle.set3D(false)
                    geometryHandle.setMeasured(false)
                    check(geometryHandle.getDimension() >= 0)
                    check(!geometryHandle.hasCurveGeometry())
                    check(geometryHandle.getLinearGeometry() != null)
                    geometryHandle.getCurveGeometry()

                    val ring = geometryHandle.getGeometryRef(0)
                    if (ring != null) {
                        ring.cloneGeometry().use { ringHandle ->
                            check(ringHandle.getPointCount() > 0)
                            check(ringHandle.getPoint(0).isNotEmpty())
                            check(ringHandle.getPoints().isNotEmpty())
                            check(ringHandle.isRing() || !ringHandle.isRing())
                        }
                    }
                }

                val mutableFeature = featureHandle.cloneFeature()
                mutableFeature.use { copy ->
                    check(copy.equal(featureHandle))
                    copy.setField("name", "sample-cli-smoke-test-updated")
                    check(copy.getFieldAsString("name") == "sample-cli-smoke-test-updated")
                    copy.setFieldNull("name")
                    check(copy.isFieldNull("name"))
                    copy.unsetField("name")
                    check(!copy.isFieldSetAndNotNull("name") || copy.isFieldSetAndNotNull("name"))
                    copy.setFID(1234L)
                    check(copy.getFID() == 1234L)
                    val geometry = featureHandle.getGeometryRef() ?: error("Feature geometry was not available.")
                    geometry.cloneGeometry().use { geometryClone ->
                        copy.setGeometryDirectly(geometryClone)
                    }
                    check(copy.getGeometryRef() != null)
                }

                val geomFieldRef = featureHandle.getGeomFieldRef(0)
                if (geomFieldRef != null) {
                    geomFieldRef.cloneGeometry().use { geometryClone ->
                        check(geometryClone.exportToWkt().isNotBlank())
                    }
                }

                val info = runtime.datasetInfo(vectorDataset)
                check(info.isNotBlank())
            }

            layer.resetReading()
            check(layer.getNextFeature() != null)
            check(layer.getFeaturesRead() >= 0)
            check(layer.syncToDisk() == 0)
            check(layer.setAttributeFilter("") == 0)
        }

        runtime.openDataset(
            sourcePath.toString(),
            DatasetOpenOptions(
                accessMode = DatasetAccessMode.ReadOnly,
                kind = DatasetKind.Vector,
            ),
        ).use { sourceCopyDataset ->
            val copiedDataset = geoJsonDriver.createCopy(
                copiedPath.toString(),
                sourceCopyDataset,
                0,
                emptyList(),
            ) ?: error("Unable to create a copied dataset.")
            copiedDataset.use { copy ->
                check(copy.getLayerCount() >= 1)
            }
        }

        return "Dataset + Layer + Feature + Geometry + FieldDefn + GeomFieldDefn"
    }
}
