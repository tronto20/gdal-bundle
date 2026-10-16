#!/usr/bin/env python3
"""Generate checked adapters from the public interfaces. No unsupported-method stubs.

The signatures come from gdal4k-runtime. C API mappings and exceptions are explicit
below; unknown signatures fail generation. The generated C++ is compiled on every
engine target, so an API/version mismatch cannot become a published stub.
"""
from pathlib import Path
import re
ROOT = Path(__file__).resolve().parents[2]
TYPES = ['GdalRuntime','Dataset','Driver','Band','SpatialReference','CoordinateTransformation','GCP','Layer','Feature','FeatureDefn','FieldDefn','GeomFieldDefn','Geometry']

def methods(cls):
    text = '\n'.join(p.read_text() for p in (ROOT/'gdal4k-runtime/src/commonMain/kotlin/dev/gdal4k/runtime').glob('*.kt'))
    body = re.search(r'interface '+cls+r'\s*\{(.*?)\n\}',text,re.S)[1]
    body = re.sub(r'/\*.*?\*/','',body,flags=re.S)
    body = re.sub(r'\s*=\s*\w+\(\)', '', body)
    for m in re.finditer(r'fun (\w+)\((.*?)\)(?:\s*:\s*([^\n]+))?',body,re.S):
        name,raw,ret=m.groups();params=[]
        # Defaults containing parentheses (intArrayOf()) are removed before parsing.
        raw=re.sub(r'\s*=\s*intArrayOf\(\)','',raw)
        for part in raw.split(','):
            part=part.strip()
            if not part:continue
            key,typ=part.split(':',1);typ=typ.split('=')[0].strip();params.append((key.strip(),typ))
        yield name,params,(ret or 'Unit').strip()

def key(name,params): return name+''.join('_'+t.replace('?','').replace('<','_').replace('>','') for _,t in params)

def kt_arg(name,typ):
    if typ.rstrip('?') in TYPES:return f'handle({name})'
    if typ in ['DatasetOpenOptions','DatasetInfoOptions']:return f'encode({name})'
    return f'encode({name})'

def kt_result(typ,expr,parent):
    nullable=typ.endswith('?');t=typ.rstrip('?')
    if t in TYPES:
        out=f'engine.wrap{t}({expr}, {parent})'
        return out if nullable else f'requireNotNull({out})'
    converters={'Int':'int','Long':'content.toLong()','Double':'content.toDouble()','String':'contentOrNull.orEmpty()','Boolean':'boolean'}
    if t in converters:
        out=f'{expr}.jsonPrimitive.{converters[t]}'
    elif t in ['DoubleArray','IntArray','ByteArray','ShortArray','FloatArray','LongArray']:
        k=t[:-5];cv={'Double':'toDouble','Int':'toInt','Byte':'toInt().toByte','Short':'toInt().toShort','Float':'toFloat','Long':'toLong'}[k]
        out=f'{expr}.jsonArray.map {{ it.jsonPrimitive.content.{cv}() }}.to{t}()'
    elif t=='List<String>':out=f'{expr}.jsonArray.map {{ it.jsonPrimitive.content }}'
    elif t=='List<DoubleArray>':out=f'{expr}.jsonArray.map {{ p -> p.jsonArray.map {{ it.jsonPrimitive.content.toDouble() }}.toDoubleArray() }}'
    elif t=='List<GCP>':out=f'{expr}.jsonArray.map {{ requireNotNull(engine.wrapGCP(it, null)) }}'
    elif t=='Unit':out=f'{expr}.let {{ Unit }}'
    else:raise ValueError(('Return',typ))
    return f'{expr}.takeUnless {{ it is JsonNull }}?.let {{ v -> '+out.replace(expr,'v')+' }' if nullable else out

# C API names for ordinary scalar and handle operations.
PREFIX={'GdalRuntime':'','Dataset':'GDAL','Driver':'GDAL','Band':'GDAL','SpatialReference':'OSR','CoordinateTransformation':'OCT','Layer':'OGR_L_','Feature':'OGR_F_','FeatureDefn':'OGR_FD_','FieldDefn':'OGR_Fld_','GeomFieldDefn':'OGR_GFld_','Geometry':'OGR_G_'}
ALIASES={
'Dataset':{'getProjection':'GDALGetProjectionRef','getDriver':'GDALGetDatasetDriver','getLayerCount':'GDALDatasetGetLayerCount','getLayer_Int':'GDALDatasetGetLayer','getLayer_String':'GDALDatasetGetLayerByName','resetReading':'GDALDatasetResetReading','testCapability':'GDALDatasetTestCapability'},
'Driver':{'getShortName':'GDALGetDriverShortName','getLongName':'GDALGetDriverLongName','getHelpTopic':'GDALGetDriverHelpTopic','register':'GDALRegisterDriver','deregister':'GDALDeregisterDriver','delete':'GDALDeleteDataset'},
'Band':{'getXSize':'GDALGetRasterBandXSize','getYSize':'GDALGetRasterBandYSize','getRasterDataType':'GDALGetRasterDataType','getColorInterpretation':'GDALGetRasterColorInterpretation','setColorInterpretation':'GDALSetRasterColorInterpretation','getNoDataValue':'GDALGetRasterNoDataValue','setNoDataValue':'GDALSetRasterNoDataValue','deleteNoDataValue':'GDALDeleteRasterNoDataValue','getUnitType':'GDALGetRasterUnitType','setUnitType':'GDALSetRasterUnitType','getCategoryNames':'GDALGetRasterCategoryNames','setCategoryNames':'GDALSetRasterCategoryNames','getSampleOverview':'GDALGetRasterSampleOverviewEx','flushCache':'GDALFlushRasterCache'},
'SpatialReference':{'cloneSpatialReference':'OSRClone','importFromEPSGA':'OSRImportFromEPSGA','getName':'OSRGetName','hasPointMotionOperation':'OSRHasPointMotionOperation','cloneGeogCS':'OSRCloneGeogCS'},
'CoordinateTransformation':{'getInverse':'OCTGetInverse'},
'Feature':{'getDefinition':'OGR_F_GetDefnRef','cloneFeature':'OGR_F_Clone','getFieldDefn':'OGR_F_GetFieldDefnRef','getGeomFieldDefn':'OGR_F_GetGeomFieldDefnRef','getGeomFieldRef':'OGR_F_GetGeomFieldRef','setGeomField':'OGR_F_SetGeomField','getFieldAsInteger64':'OGR_F_GetFieldAsInteger64'},
'FeatureDefn':{'getFieldDefn':'OGR_FD_GetFieldDefn','getGeomFieldDefn':'OGR_FD_GetGeomFieldDefn'},
'FieldDefn':{'getName':'OGR_Fld_GetNameRef','getAlternativeName':'OGR_Fld_GetAlternativeNameRef','getFieldType':'OGR_Fld_GetType','getTypeName':'OGR_GetFieldTypeName','getDomainName':'OGR_Fld_GetDomainName','getDomainNameRef':'OGR_Fld_GetDomainName','getComment':'OGR_Fld_GetComment','getCommentRef':'OGR_Fld_GetComment'},
'GeomFieldDefn':{'getName':'OGR_GFld_GetNameRef'},
'Geometry':{'cloneGeometry':'OGR_G_Clone','getArea':'OGR_G_Area','geodesicLength':'OGR_G_GeodesicLength','geodesicArea':'OGR_G_GeodesicArea','equal':'OGR_G_Equals','getCoordinateDimension':'OGR_G_GetCoordinateDimension','setCoordinateDimension':'OGR_G_SetCoordinateDimension','getDimension':'OGR_G_GetDimension','wkbSize':'OGR_G_WkbSizeEx','hasCurveGeometry':'OGR_G_HasCurveGeometry'},
}
OWNED={('Driver','create'),('Driver','createCopy'),('Driver','createVector'),('GdalRuntime','openDataset'),('GdalRuntime','createSpatialReference'),('GdalRuntime','createCoordinateTransformation'),('GdalRuntime','createGCP'),('Feature','cloneFeature'),('Layer','getFeature'),('Layer','getNextFeature'),('SpatialReference','cloneSpatialReference'),('SpatialReference','cloneGeogCS'),('SpatialReference','convertToOtherProjection'),('CoordinateTransformation','getInverse')}
for m in ['cloneGeometry','centroid','pointOnSurface','boundary','convexHull','buffer','simplify','simplifyPreserveTopology','makeValid','normalize','getLinearGeometry','getCurveGeometry']:OWNED.add(('Geometry',m))
SPECIAL={}
def special(cls,name,body):SPECIAL[(cls,name)]=body

special('GdalRuntime','writeMemoryFile','auto path=a["path"].ToString();require(path.rfind("/vsimem/",0)==0,"Memory file path must start with /vsimem/");auto slash=path.find_last_of(char(47));VSIMkdirRecursive(path.substr(0,slash).c_str(),0700);auto f=VSIFOpenL(path.c_str(),"wb");require(f!=nullptr,"Cannot create memory file");auto input=a.GetArray("bytes");std::vector<GByte> data;for(auto v:input)data.push_back(static_cast<GByte>(v.ToInteger()));auto written=VSIFWriteL(data.data(),1,data.size(),f);auto code=VSIFCloseL(f);require(written==data.size()&&code==0,"Cannot write memory file");return nullValue();')
special('GdalRuntime','readMemoryFile','auto path=a["path"].ToString();require(path.rfind("/vsimem/",0)==0,"Memory file path must start with /vsimem/");vsi_l_offset size=0;auto p=VSIGetMemFileBuffer(path.c_str(),&size,false);require(p!=nullptr,"Memory file does not exist");return numbers(p,size);')
special('GdalRuntime','deleteMemoryFile','auto path=a["path"].ToString();require(path.rfind("/vsimem/",0)==0,"Memory file path must start with /vsimem/");VSIStatBufL info;if(VSIStatL(path.c_str(),&info)==0){auto code=VSI_ISDIR(info.st_mode)?VSIRmdirRecursive(path.c_str()):VSIUnlink(path.c_str());require(code==0,"Cannot remove memory file");}return nullValue();')

special('GdalRuntime','versionInfo','auto request=a["request"].ToString();return value(request=="THIRD_PARTY_LICENSES"?gdal4k_license_notices():GDALVersionInfo(request.c_str()));')

# Bodies have access to h (receiver), a (named JSON arguments), and engine r.
# Each returns an object with a "value" member.
special('GdalRuntime','getDriver','return r.result(GDALGetDriverByName(a["name"].ToString().c_str()), "Driver", false);')
special('GdalRuntime','createSpatialReference','auto s=OSRNewSpatialReference(nullptr); if(a["definition"].IsValid() && OSRSetFromUserInput(s,a["definition"].ToString().c_str())!=OGRERR_NONE) { OSRDestroySpatialReference(s); throw std::runtime_error(CPLGetLastErrorMsg()); } return r.result(s,"SpatialReference",true);')
special('GdalRuntime','createCoordinateTransformation','return r.result(OCTNewCoordinateTransformation(r.arg(a["source"]),r.arg(a["target"])),"CoordinateTransformation",true);')
special('GdalRuntime','createGCP','auto p=static_cast<GDAL_GCP*>(CPLCalloc(1,sizeof(GDAL_GCP))); GDALInitGCPs(1,p); p->dfGCPX=a["gcpX"].ToDouble();p->dfGCPY=a["gcpY"].ToDouble();p->dfGCPZ=a["gcpZ"].ToDouble();p->dfGCPPixel=a["gcpPixel"].ToDouble();p->dfGCPLine=a["gcpLine"].ToDouble();CPLFree(p->pszInfo);CPLFree(p->pszId);p->pszInfo=CPLStrdup(a["info"].ToString().c_str());p->pszId=CPLStrdup(a["id"].ToString().c_str());return r.result(p,"GCP",true);')
special('GdalRuntime','openDataset','auto opt=a["options"]; auto flags=GDAL_OF_VERBOSE_ERROR | (opt.GetBool("update")?GDAL_OF_UPDATE:GDAL_OF_READONLY) | opt.GetInteger("kind"); Strings list(opt.GetArray("options")); auto ds=GDALOpenEx(a["source"].ToString().c_str(),flags,nullptr,list.data(),nullptr);if(!ds) throw std::runtime_error(CPLGetLastErrorMsg());return r.result(ds,"Dataset",true);')
special('GdalRuntime','datasetInfo','Strings list(a["options"].GetArray("arguments"));auto ds=r.arg(a["dataset"]);char* s=nullptr;if(GDALGetRasterCount(ds)>0){auto opts=GDALInfoOptionsNew(list.data(),nullptr);if(!opts)throw std::runtime_error(CPLGetLastErrorMsg());s=GDALInfo(ds,opts);GDALInfoOptionsFree(opts);}else {auto opts=GDALVectorInfoOptionsNew(list.data(),nullptr);if(!opts)throw std::runtime_error(CPLGetLastErrorMsg());s=GDALVectorInfo(ds,opts);GDALVectorInfoOptionsFree(opts);}if(!s)throw std::runtime_error(CPLGetLastErrorMsg());return allocated(s);')
special('Dataset','getDriverShortName','return value(GDALGetDriverShortName(GDALGetDatasetDriver(h)));')
special('Dataset','getDriverLongName','return value(GDALGetDriverLongName(GDALGetDatasetDriver(h)));')
special('Dataset','getGeoTransform','double out[6]; if(GDALGetGeoTransform(h,out)!=CE_None)return nullValue();return numbers(out,6);')
special('Dataset','setGeoTransform','auto v=doubles(a.GetArray("geoTransform")); require(v.size()==6,"GeoTransform must have six elements");return value(GDALSetGeoTransform(h,v.data()));')
special('Dataset','getExtent','OGREnvelope e; if(GDALDataset::FromHandle(h)->GetExtent(&e)!=OGRERR_NONE)return nullValue(); return numbers({e.MinX,e.MaxX,e.MinY,e.MaxY});')
special('Dataset','getFileList','auto p=GDALGetFileList(h);auto out=strings(p);CSLDestroy(p);return out;')
special('Dataset','buildOverviews','auto v=ints(a.GetArray("overviewBands"));return value(GDALBuildOverviews(h,a["resampling"].ToString().c_str(),static_cast<int>(v.size()),v.data(),0,nullptr,nullptr,nullptr));')
special('Dataset','getGCPs','CPLJSONArray out;auto p=GDALGetGCPs(h);for(int i=0;i<GDALGetGCPCount(h);i++){auto q=GDALDuplicateGCPs(1,p+i);out.Add(r.result(q,"GCP",true)["value"]);}return value(out);')
special('Dataset','setGCPs','std::vector<GDAL_GCP> v; for(auto p:a.GetArray("gcps"))v.push_back(*static_cast<GDAL_GCP*>(r.arg(p)));if(a["projection"].IsValid())return value(GDALSetGCPs(h,static_cast<int>(v.size()),v.data(),a["projection"].ToString().c_str()));return value(GDALSetGCPs2(h,static_cast<int>(v.size()),v.data(),r.arg(a["spatialReference"])));')
special('Driver','create','Strings list(a.GetArray("options"));return r.result(GDALCreate(h,a["name"].ToString().c_str(),a.GetInteger("xSize"),a.GetInteger("ySize"),a.GetInteger("bandCount"),static_cast<GDALDataType>(a.GetInteger("dataType")),list.data()),"Dataset",true);')
special('Driver','createVector','Strings list(a.GetArray("options"));return r.result(GDALCreate(h,a["name"].ToString().c_str(),0,0,0,GDT_Unknown,list.data()),"Dataset",true);')
special('Driver','createCopy','Strings list(a.GetArray("options"));return r.result(GDALCreateCopy(h,a["name"].ToString().c_str(),r.arg(a["dataset"]),a.GetInteger("strict"),list.data(),nullptr,nullptr),"Dataset",true);')
for method,fn in [('rename','GDALRenameDataset'),('copyFiles','GDALCopyDatasetFiles')]:special('Driver',method,f'return value({fn}(h,a["to"].ToString().c_str(),a["from"].ToString().c_str()));')
special('Driver','hasOpenOption','return value(GDALDriverHasOpenOption(h,a["option"].ToString().c_str())!=0);')
special('Band','getBlockSize','int x,y;GDALGetBlockSize(h,&x,&y);CPLJSONArray out;out.Add(x);out.Add(y);return value(out);')
special('Band','checksum','return value(GDALChecksumImage(h,0,0,GDALGetRasterBandXSize(h),GDALGetRasterBandYSize(h)));')
special('Band','getNoDataValue','int ok;auto v=GDALGetRasterNoDataValue(h,&ok);return ok?value(v):nullValue();')
special('Band','getCategoryNames','return strings(GDALGetRasterCategoryNames(h));')
special('Band','setCategoryNames','Strings list(a.GetArray("names"));return value(GDALSetRasterCategoryNames(h,list.data()));')
for method in ['readRaster','writeRaster']:special('Band',method,'return raster(h,a,method.find("writeRaster")==0,method);')
for axis in ['X','Y','Z','Pixel','Line']:
    special('GCP','getGCP'+axis,f'return value(static_cast<GDAL_GCP*>(h)->dfGCP{axis});')
    special('GCP','setGCP'+axis,f'static_cast<GDAL_GCP*>(h)->dfGCP{axis}=a["value"].ToDouble();return nullValue();')
for field,member in [('Info','pszInfo'),('Id','pszId')]:
    special('GCP','get'+field,f'return value(static_cast<GDAL_GCP*>(h)->{member});')
    special('GCP','set'+field,f'auto p=static_cast<GDAL_GCP*>(h);CPLFree(p->{member});p->{member}=CPLStrdup(a["{field.lower()}"].ToString().c_str());return nullValue();')
for method,fn in [('exportToWkt','OSRExportToWkt'),('exportToPrettyWkt','OSRExportToPrettyWkt'),('exportToProj4','OSRExportToProj4'),('exportToProjJson','OSRExportToPROJJSON'),('exportToXml','OSRExportToXML'),('exportToMICoordSys','OSRExportToMICoordSys')]:
    extra=',false' if method=='exportToPrettyWkt' else ',nullptr' if method=='exportToProjJson' else ',a["rootElement"].ToString().c_str()' if method=='exportToXml' else ''
    special('SpatialReference',method,f'char* out=nullptr;auto err={fn}(h,&out{extra});if(err!=OGRERR_NONE){{CPLFree(out);throw std::runtime_error(CPLGetLastErrorMsg());}}return allocated(out);')
special('SpatialReference','importFromWkt','auto s=a["wkt"].ToString();char* p=s.data();return value(OSRImportFromWkt(h,&p));')
for method,fn in [('getProjParm','OSRGetProjParm'),('getNormProjParm','OSRGetNormProjParm')]:special('SpatialReference',method,f'return value({fn}(h,a["name"].ToString().c_str(),a["defaultValue"].ToDouble(),nullptr));')
for kind in ['Angular','Linear']:
    special('SpatialReference','get'+kind+'Units',f'return value(OSRGet{kind}Units(h,nullptr));')
    special('SpatialReference','get'+kind+'UnitsName',f'char* name=nullptr;OSRGet{kind}Units(h,&name);return value(name);')
special('SpatialReference','getUTMZone','int north;auto z=OSRGetUTMZone(h,&north);return value(north?z:-z);')
special('SpatialReference','getAxisName','OGRAxisOrientation o=OAO_Other;return value(OSRGetAxis(h,a["targetKey"].ToString().c_str(),a.GetInteger("index"),&o));')
special('SpatialReference','getAxisOrientation','OGRAxisOrientation o=OAO_Other;OSRGetAxis(h,a["targetKey"].ToString().c_str(),a.GetInteger("index"),&o);return value(o);')
special('SpatialReference','convertToOtherProjection','return r.result(OSRConvertToOtherProjection(h,a["projection"].ToString().c_str(),nullptr),"SpatialReference",true);')
for name,fn in [('promoteTo3D','OSRPromoteTo3D'),('demoteTo2D','OSRDemoteTo2D')]:special('SpatialReference',name,f'return value({fn}(h,nullptr));')
special('CoordinateTransformation','transformPoint','double x=a["x"].ToDouble(),y=a["y"].ToDouble(),z=a["z"].ToDouble();require(OCTTransform(h,1,&x,&y,&z)!=0,"Coordinate transformation failed");return numbers({x,y,z});')
special('CoordinateTransformation','transformPoints','CPLJSONArray out;for(auto p:a.GetArray("points")){auto v=doubles(p.ToArray());require(v.size()>=2,"Point must have at least two coordinates");double z=v.size()>2?v[2]:0;require(v.size()<=4,"A point must have two, three or four coordinates");auto success=v.size()==4?OCTTransform4D(h,1,&v[0],&v[1],&z,&v[3],nullptr):OCTTransform(h,1,&v[0],&v[1],&z);require(success!=0,"Coordinate transformation failed");if(v.size()>2)v[2]=z;out.Add(numbers(v.data(),v.size())["value"]);}return value(out);')
special('CoordinateTransformation','transformBounds','double x0,y0,x1,y1;require(OCTTransformBounds(h,a["xMin"].ToDouble(),a["yMin"].ToDouble(),a["xMax"].ToDouble(),a["yMax"].ToDouble(),&x0,&y0,&x1,&y1,a.GetInteger("densifyPoints"))!=0,"Bounds transformation failed");return numbers({x0,y0,x1,y1});')
special('Layer','getExtent','OGREnvelope e;if(OGR_L_GetExtent(h,&e,a.GetBool("force"))!=OGRERR_NONE)return nullValue();return numbers({e.MinX,e.MaxX,e.MinY,e.MaxY});')
special('Feature','getFieldAsStringList','return strings(OGR_F_GetFieldAsStringList(h,a.GetInteger("index")));')
for name,fn,typ in [('getFieldAsIntegerList','OGR_F_GetFieldAsIntegerList','int'),('getFieldAsDoubleList','OGR_F_GetFieldAsDoubleList','double'),('getFieldAsBinary','OGR_F_GetFieldAsBinary','GByte')]:special('Feature',name,f'int n=0;auto p={fn}(h,a.GetInteger("index"),&n);return numbers(p,n);')
special('Feature','fillUnsetWithDefault','OGR_F_FillUnsetWithDefault(h,false,nullptr);return nullValue();')
special('Feature','dumpReadableAsString','return allocated(OGR_F_DumpReadableAsString(h,nullptr));')
special('FieldDefn','getTypeName','return value(OGR_GetFieldTypeName(OGR_Fld_GetType(h)));')
special('FieldDefn','getFieldTypeName','return value(OGR_GetFieldTypeName(static_cast<OGRFieldType>(a.GetInteger("type"))));')
# Geometry functions with output parameters or options.
for name,fn in [('exportToWkt','OGR_G_ExportToWkt'),('exportToIsoWkt','OGR_G_ExportToIsoWkt')]:special('Geometry',name,f'char* p=nullptr;auto err={fn}(h,&p);if(err!=OGRERR_NONE){{CPLFree(p);throw std::runtime_error(CPLGetLastErrorMsg());}}return allocated(p);')
for name,fn in [('exportToGml','OGR_G_ExportToGML'),('exportToKml','OGR_G_ExportToKML'),('exportToJson','OGR_G_ExportToJson')]:special('Geometry',name,f'return allocated({fn}(h'+(',nullptr' if name=='exportToKml' else '')+'));')
for name,fn in [('exportToWkb','OGR_G_ExportToWkb'),('exportToIsoWkb','OGR_G_ExportToIsoWkb')]:special('Geometry',name,f'std::vector<unsigned char> out(OGR_G_WkbSizeEx(h));require({fn}(h,wkbNDR,out.data())==OGRERR_NONE,"WKB export failed");return numbers(out.data(),out.size());')
special('Geometry','getPoint','double x,y,z;OGR_G_GetPoint(h,a.GetInteger("index"),&x,&y,&z);return numbers({x,y,z});')
special('Geometry','getPoints','CPLJSONArray out;for(int i=0;i<OGR_G_GetPointCount(h);i++){double x,y,z;OGR_G_GetPoint(h,i,&x,&y,&z);out.Add(numbers({x,y,z})["value"]);}return value(out);')
special('Geometry','getEnvelope','OGREnvelope e;OGR_G_GetEnvelope(h,&e);return numbers({e.MinX,e.MaxX,e.MinY,e.MaxY});')
special('Geometry','getEnvelope3D','OGREnvelope3D e;OGR_G_GetEnvelope3D(h,&e);return numbers({e.MinX,e.MaxX,e.MinY,e.MaxY,e.MinZ,e.MaxZ});')
special('Geometry','centroid','auto p=OGR_G_CreateGeometry(wkbPoint);if(OGR_G_Centroid(h,p)!=OGRERR_NONE){OGR_G_DestroyGeometry(p);return nullValue();}return r.result(p,"Geometry",true);')
special('Geometry','buffer','if(a["options"].IsValid()){Strings opt(a.GetArray("options"));return r.result(OGR_G_BufferEx(h,a["distance"].ToDouble(),opt.data()),"Geometry",true);}return r.result(OGR_G_Buffer(h,a["distance"].ToDouble(),30),"Geometry",true);')
special('Geometry','getLinearGeometry','return r.result(OGR_G_GetLinearGeometry(h,0,nullptr),"Geometry",true);')
special('Geometry','getCurveGeometry','return r.result(OGR_G_GetCurveGeometry(h,nullptr),"Geometry",true);')
special('Geometry','hasCurveGeometry','return value(OGR_G_HasCurveGeometry(h,false)!=0);')
special('Geometry','removeGeometry','return value(OGR_G_RemoveGeometry(h,a.GetInteger("index"),true));')
special('Geometry','intersectsExact','return value(OGR_G_Intersects(h,r.arg(a["other"]))!=0);')
for name,fn in [('geodesicLength','OGR_G_GeodesicLength'),('geodesicArea','OGR_G_GeodesicArea')]:special('Geometry',name,f'return value({fn}(h));')

cpp=[];kt=['// Generated by tools/generate-runtime.py. Edit the mapping, not this file.','package dev.gdal4k.binary','','import dev.gdal4k.runtime.*','import kotlinx.serialization.json.*','']
for cls in TYPES:
    base='RuntimeHandle(engine, id, parent)' if cls!='GdalRuntime' else 'RuntimeHandle(engine, "0", null)'
    args='engine: RuntimeEngine, id: String, parent: RuntimeHandle?' if cls!='GdalRuntime' else 'engine: RuntimeEngine'
    kt.append(f'internal class Bundled{cls}({args}) : {base}, {cls} {{')
    for name,params,ret in methods(cls):
        sig=key(name,params);argstr=', '.join(f'{n}: {t}' for n,t in params)
        if name=='close':kt.append('    override suspend fun close() = closeHandle()');continue
        payload=', '.join('"'+n+'" to '+kt_arg(n,t) for n,t in params)
        call=f'invoke("{cls}", "{sig}", mapOf({payload}))'
        if cls=='Band' and name in ['readRaster','writeRaster']:
            typ=dict(params)['buffer'];cv={'ByteArray':'toInt().toByte','ShortArray':'toInt().toShort','IntArray':'toInt','LongArray':'toLong','FloatArray':'toFloat','DoubleArray':'toDouble'}[typ]
            body=f'val result = {call}.jsonObject; '+('result["buffer"]!!.jsonArray.forEachIndexed { i, v -> buffer[i] = v.jsonPrimitive.content.'+cv+'() }; ' if name=='readRaster' else '')+'return result["code"]!!.jsonPrimitive.int'
        elif cls=='CoordinateTransformation' and name=='transformPoints':
            body=f'val result = {call}.jsonArray; result.forEachIndexed {{ i, p -> p.jsonArray.forEachIndexed {{ j, v -> points[i][j] = v.jsonPrimitive.content.toDouble() }} }}'
        elif cls=='CoordinateTransformation' and name=='transformBounds':
            body=f'require(bounds.size >= 4); {call}.jsonArray.forEachIndexed {{ i, v -> bounds[i] = v.jsonPrimitive.content.toDouble() }}'
        elif ret=='Unit':
            body=call+'; return Unit'
        else:
            parent='null' if (cls,name) in OWNED else ('null' if cls=='GdalRuntime' else 'this')
            body='val result = '+call+'; return '+kt_result(ret,'result',parent)
        kt.append(f'    override suspend fun {name}({argstr}): {ret} {{ {body} }}')
        cpp.append(f'    if(type=="{cls}" && method=="{sig}") {{')
        custom=SPECIAL.get((cls,name))
        if custom:cpp.append('        '+custom)
        else:
            fn=ALIASES.get(cls,{}).get(sig,ALIASES.get(cls,{}).get(name,PREFIX[cls]+name[0].upper()+name[1:]))
            callargs=[] if cls=='GdalRuntime' else ['Arg(r,h)']
            # Named field overloads resolve to the same indexed GDAL C API.
            for n,t in params:
                if cls=='Feature' and t=='String' and n=='name' and name not in ['getFieldIndex','getGeomFieldIndex']:
                    indexfn='OGR_F_GetGeomFieldIndex' if 'GeomField' in name else 'OGR_F_GetFieldIndex'
                    callargs.append(f'Arg(r, {indexfn}(h,a["name"].ToString().c_str()))')
                else:callargs.append(f'Arg(r,a["{n}"])')
            if cls=='Feature' and name=='setField':fn='OGR_F_SetField'+{'String':'String','Int':'Integer','Double':'Double'}[params[-1][1]]
            if cls=='SpatialReference' and name in ['setAxisMappingStrategy']:pass
            if cls=='Feature' and name=='getFieldType':
                idx='a.GetInteger("index")' if params[0][1]=='Int' else 'OGR_F_GetFieldIndex(h,a["name"].ToString().c_str())'
                cpp.append(f'        return value(OGR_Fld_GetType(OGR_F_GetFieldDefnRef(h,{idx})));');cpp.append('    }');continue
            if cls=='Feature' and name in ['setGeometryDirectly','setGeomFieldDirectly']:
                # Copy into the feature, then release the transferred source only on success.
                fn='OGR_F_SetGeometry' if name=='setGeometryDirectly' else 'OGR_F_SetGeomField'
            if cls=='Geometry' and name=='addGeometryDirectly':fn='OGR_G_AddGeometry'
            if cls=='Dataset' and name=='flushCache':fn='GDALFlushCache'
            if cls=='Geometry' and name=='addPoint' and len(params)==2:fn='OGR_G_AddPoint_2D'
            if cls=='Geometry' and name=='setPoint' and len(params)==3:fn='OGR_G_SetPoint_2D'
            if cls=='Geometry' and name=='addPoint2D':fn='OGR_G_AddPoint_2D'
            if cls=='Geometry' and name=='setPoint2D':fn='OGR_G_SetPoint_2D'
            expr=f'apply({fn}, '+', '.join(callargs)+')'
            t=ret.rstrip('?')
            if t in TYPES:
                owned=(cls,name) in OWNED
                cpp.append(f'        return r.result({expr},"{t}",{str(owned).lower()},'+('0' if owned or cls=='GdalRuntime' else 'id')+');')
            elif ret=='Unit':cpp.append(f'        {expr}; return nullValue();')
            elif ret=='Boolean':cpp.append(f'        return value({expr}!=0);')
            elif ret=='Long':cpp.append(f'        return value(std::to_string({expr}));')
            elif t=='List<String>':raise ValueError(('needs special',cls,name))
            else:
                cpp.append(f'        auto out={expr};'+('if(out==0) r.close(std::stoull(a["geometry"].GetString("handle")));' if name in ['setGeometryDirectly','setGeomFieldDirectly','addGeometryDirectly'] else '')+'return value(out);')
        cpp.append('    }')
    kt.append('}\n')
kt.append('internal fun RuntimeEngine.wrap(type: String, value: JsonElement, parent: RuntimeHandle?): RuntimeHandle? {\n    if (value is JsonNull) return null\n    val id = value.jsonPrimitive.content\n    return when(type) {')
for cls in TYPES[1:]:kt.append(f'        "{cls}" -> Bundled{cls}(this, id, parent)')
kt.append('        else -> error("Unknown GDAL handle type: $type")\n    }\n}')
for cls in TYPES[1:]:kt.append(f'internal fun RuntimeEngine.wrap{cls}(value: JsonElement, parent: RuntimeHandle?): {cls}? = wrap("{cls}", value, parent) as {cls}?')
(ROOT/'gdal4k-binary/src/commonMain/kotlin/dev/gdal4k/binary/BundledRuntime.generated.kt').write_text('\n'.join(kt)+'\n')
(ROOT/'gdal4k/native/runtime.generated.inc').write_text('// Generated by gdal4k-binary/tools/generate-runtime.py\n'+'\n'.join(cpp)+'\n')
