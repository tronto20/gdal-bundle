#include "probe.h"
#include "sample_tif.h"
#include <gdal.h>
#include <ogr_api.h>
#include <ogr_srs_api.h>
#include <cpl_vsi.h>
#include <cpl_conv.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

/* Distinct codes identify the first failed capability; never return a fake success. */
int gdal4k_probe(void) {
    GDALAllRegister();
    VSILFILE *file = VSIFileFromMemBuffer("/vsimem/probe.tif", (GByte *)sample_tif, sizeof(sample_tif), FALSE);
    if (!file) return 10;
    VSIFCloseL(file);
    GDALDatasetH ds = GDALOpenEx("/vsimem/probe.tif", GDAL_OF_RASTER | GDAL_OF_READONLY, NULL, NULL, NULL);
    if (!ds) return 11;
    int result = 0;
    GDALDatasetH copy = NULL, cog = NULL, vector = NULL;
    OGRSpatialReferenceH src = NULL, dst = NULL;
    OGRCoordinateTransformationH transform = NULL;
    unsigned char pixels[6] = {0};
    const unsigned char expected[] = {0, 1, 2, 3, 4, 255};
    double gt[6];
    int has_nodata = 0;
    GDALRasterBandH band = GDALGetRasterBand(ds, 1);
    if (GDALGetRasterXSize(ds) != 3 || GDALGetRasterYSize(ds) != 2 || GDALGetRasterCount(ds) != 1) { result = 12; goto cleanup; }
    if (GDALRasterIO(band, GF_Read, 0, 0, 3, 2, pixels, 3, 2, GDT_Byte, 0, 0) != CE_None || memcmp(pixels, expected, 6)) { result = 13; goto cleanup; }
    if (GDALGetRasterNoDataValue(band, &has_nodata) != 0 || !has_nodata) { result = 14; goto cleanup; }
    if (GDALGetGeoTransform(ds, gt) != CE_None || gt[0] != 100 || gt[1] != 0.5 || gt[3] != 50 || gt[5] != -0.5) { result = 15; goto cleanup; }
    if (!GDALGetDriverByName("GTiff")) { result = 16; goto cleanup; }
    copy = GDALCreateCopy(GDALGetDriverByName("GTiff"), "/vsimem/roundtrip.tif", ds, FALSE, NULL, NULL, NULL);
    if (!copy) { result = 17; goto cleanup; }
    GDALClose(copy); copy = NULL;
    copy = GDALOpen("/vsimem/roundtrip.tif", GA_ReadOnly);
    if (!copy || GDALRasterIO(GDALGetRasterBand(copy, 1), GF_Read, 0, 0, 3, 2, pixels, 3, 2, GDT_Byte, 0, 0) != CE_None || memcmp(pixels, expected, 6)) { result = 18; goto cleanup; }
    if (!GDALGetDriverByName("COG")) { result = 19; goto cleanup; }
    cog = GDALCreateCopy(GDALGetDriverByName("COG"), "/vsimem/output.tif", ds, FALSE, NULL, NULL, NULL);
    if (!cog) { result = 20; goto cleanup; }
    GDALClose(cog); cog = GDALOpen("/vsimem/output.tif", GA_ReadOnly);
    if (!cog || GDALRasterIO(GDALGetRasterBand(cog, 1), GF_Read, 0, 0, 3, 2, pixels, 3, 2, GDT_Byte, 0, 0) != CE_None || memcmp(pixels, expected, 6)) { result = 24; goto cleanup; }
    const char *layout = GDALGetMetadataItem(cog, "LAYOUT", "IMAGE_STRUCTURE");
    if (!layout || strcmp(layout, "COG")) { result = 25; goto cleanup; }
    src = OSRNewSpatialReference(NULL); dst = OSRNewSpatialReference(NULL);
    if (OSRImportFromEPSG(src, 4326) || OSRImportFromEPSG(dst, 3857)) { result = 21; goto cleanup; }
    OSRSetAxisMappingStrategy(src, OAMS_TRADITIONAL_GIS_ORDER);
    OSRSetAxisMappingStrategy(dst, OAMS_TRADITIONAL_GIS_ORDER);
    transform = OCTNewCoordinateTransformation(src, dst);
    double x = 10, y = 45;
    if (!transform || !OCTTransform(transform, 1, &x, &y, NULL) || fabs(x - 1113194.9079327357) > 0.01 || fabs(y - 5621521.486192066) > 0.01) { result = 22; goto cleanup; }
    vector = GDALOpenEx("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[10,45]},\"properties\":{}}]}", GDAL_OF_VECTOR, NULL, NULL, NULL);
    if (!vector || GDALDatasetGetLayerCount(vector) != 1 || OGR_L_GetFeatureCount(GDALDatasetGetLayer(vector, 0), TRUE) != 1) { result = 23; goto cleanup; }
    printf("GDAL_VERSION=%s raster=ok nodata=ok geotransform=ok gtiff_roundtrip=ok cog=ok crs=ok vector=ok\n", GDALVersionInfo("RELEASE_NAME"));
cleanup:
    if (vector) GDALClose(vector);
    if (transform) OCTDestroyCoordinateTransformation(transform);
    if (src) OSRDestroySpatialReference(src);
    if (dst) OSRDestroySpatialReference(dst);
    if (cog) GDALClose(cog);
    if (copy) GDALClose(copy);
    GDALClose(ds);
    VSIUnlink("/vsimem/probe.tif"); VSIUnlink("/vsimem/roundtrip.tif"); VSIUnlink("/vsimem/output.tif");
    return result;
}
