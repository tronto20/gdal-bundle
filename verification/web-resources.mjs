// Exercises GDAL's bundled file finder and lazy data reads through the public protocol.
export async function exerciseResources(bridge) {
    async function call(type, handle, method, parameters = {}) {
        const response = JSON.parse(await bridge.call(JSON.stringify({op:'runtime', args:{type, handle, method, parameters}})));
        if (response.error) throw new Error(response.error);
        return response.result.value;
    }
    const driver = await call('GdalRuntime','0','getDriver_String',{name:'MEM'});
    const dataset = await call('Driver',driver,'create_String_Int_Int_Int_Int_List_String',{name:'',xSize:16,ySize:16,bandCount:1,dataType:1,options:[]});
    try {
        const crs = await call('GdalRuntime','0','createSpatialReference_String',{definition:'EPSG:2193'});
        const projection = await call('SpatialReference',crs,'exportToWkt');
        await call('Dataset',dataset,'setProjection_String',{projection});
        await call('Handle',crs,'close');
        await call('Dataset',dataset,'setGeoTransform_DoubleArray',{geoTransform:[1700000,10,0,5400000,0,-10]});
        const band = await call('Dataset',dataset,'getRasterBand_Int',{index:1});
        await call('Band',band,'writeRaster_ByteArray',{buffer:Array(256).fill(42)});
        const files = await call('Dataset',dataset,'translate_String_String_List_String',{driver:'COG',name:'nztm.tif',arguments:['-co','TILING_SCHEME=NZTM2000','-co','ZOOM_LEVEL=12']});
        const output = await call('GdalRuntime','0','openDataset_GdalFiles_DatasetOpenOptions',{files,options:{update:false,kind:2,options:[]}});
        try {
            if (!(await call('Dataset',output,'getProjection')).includes('2193')) throw new Error('Bundled tiling scheme was not used');
        } finally { await call('Handle',output,'close'); }
    } finally { await call('Handle',dataset,'close'); }
}
