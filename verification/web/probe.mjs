const assert = (condition, message) => { if (!condition) throw new Error(message); };

// Runs inside GDAL Wasm on both Node and the browser. Kotlin initiates this call.
export async function runProbe(gdal, fixture, vectorFixture) {
  const opened = await gdal.open(fixture);
  assert(opened.errors.length === 0 && opened.datasets.length === 1, JSON.stringify(opened.errors));
  const ds = opened.datasets[0];
  const datasets = [ds];
  try {
    const info = await gdal.gdalinfo(ds);
    assert(JSON.stringify(info.size) === '[3,2]', 'Raster dimensions');
    assert(info.bands.length === 1 && info.bands[0].noDataValue === 0, 'Band/NoData');
    assert(JSON.stringify(info.geoTransform) === '[100,0.5,0,50,0,-0.5]', 'Geotransform');
    const xyz = await gdal.gdal_translate(ds, ['-of', 'XYZ']);
    const values = new TextDecoder().decode(await gdal.getFileBytes(xyz)).trim().split(/\r?\n/).map(line => Number(line.trim().split(/\s+/)[2]));
    assert(JSON.stringify(values) === '[0,1,2,3,4,255]', 'Pixel values');
    const output = await gdal.gdal_translate(ds, ['-of', 'GTiff']);
    const bytes = await gdal.getFileBytes(output);
    assert(bytes.byteLength > 6, 'GTiff output');
    // Reopen the actual output in the same GDAL virtual filesystem.
    const roundtrip = await gdal.open(output.local);
    assert(roundtrip.errors.length === 0 && roundtrip.datasets.length === 1, 'GTiff reopen');
    datasets.push(roundtrip.datasets[0]);
    const xyz2 = await gdal.gdal_translate(roundtrip.datasets[0], ['-of', 'XYZ']);
    const values2 = new TextDecoder().decode(await gdal.getFileBytes(xyz2)).trim().split(/\r?\n/).map(line => Number(line.trim().split(/\s+/)[2]));
    assert(JSON.stringify(values2) === JSON.stringify(values), 'GTiff roundtrip pixels');
    const cog = await gdal.gdal_translate(ds, ['-of', 'COG']);
    assert((await gdal.getFileBytes(cog)).byteLength > 6, 'COG output');
    const reopenedCog = await gdal.open(cog.local);
    assert(reopenedCog.errors.length === 0 && reopenedCog.datasets.length === 1, 'COG reopen');
    datasets.push(reopenedCog.datasets[0]);
    const cogInfo = await gdal.gdalinfo(reopenedCog.datasets[0]);
    assert(cogInfo.metadata?.IMAGE_STRUCTURE?.LAYOUT === 'COG', 'COG layout');
    const cogXyz = await gdal.gdal_translate(reopenedCog.datasets[0], ['-of', 'XYZ']);
    const cogValues = new TextDecoder().decode(await gdal.getFileBytes(cogXyz)).trim().split(/\r?\n/).map(line => Number(line.trim().split(/\s+/)[2]));
    assert(JSON.stringify(cogValues) === JSON.stringify(values), 'COG pixels');
    const transformed = await gdal.gdaltransform([[10,45]], ['-s_srs','EPSG:4326','-t_srs','EPSG:3857']);
    assert(Math.abs(transformed[0][0] - 1113194.9079327357) < 0.01 && Math.abs(transformed[0][1] - 5621521.486192066) < 0.01, 'CRS transform');
    const vector = await gdal.open(vectorFixture);
    assert(vector.errors.length === 0 && vector.datasets.length === 1, 'GeoJSON open');
    datasets.push(vector.datasets[0]);
    const vectorInfo = await gdal.getInfo(vector.datasets[0]);
    assert(vectorInfo.layerCount === 1 && vectorInfo.featureCount === 1, 'GeoJSON features');
    console.log('GDAL_WEB_PROBE raster=ok nodata=ok geotransform=ok gtiff_roundtrip=ok cog=ok crs=ok vector=ok');
    return 0;
  } finally {
    for (const dataset of datasets.reverse()) await gdal.close(dataset);
  }
}
