#pragma once
#include <gdal_priv.h>
#include <gdal_alg.h>
#include <gdal_utils.h>
#include <cpl_vsi.h>
#include <ogr_api.h>
#include <ogr_srs_api.h>
#include <cpl_json.h>
#include <cpl_string.h>
#include <functional>
#include <map>
#include <memory>
#include <limits>
#include <stdexcept>
#include <type_traits>
#include <vector>

namespace g4k {
inline void require(bool ok,const char* message) { if(!ok) throw std::runtime_error(message); }
inline CPLJSONObject nullValue() { CPLJSONObject o; o.AddNull("value");return o; }
inline CPLJSONObject value(const char* v) { if(!v)return nullValue();CPLJSONObject o;o.Add("value",v);return o; }
inline CPLJSONObject value(const std::string& v) { return value(v.c_str()); }
inline CPLJSONObject value(const CPLJSONArray& v) { CPLJSONObject o;o.Add("value",v);return o; }
template<class T> CPLJSONObject value(T v) {
    CPLJSONObject o;
    if constexpr(std::is_enum_v<T>)o.Add("value",static_cast<int>(v));
    else if constexpr(std::is_floating_point_v<T>) {if(std::isfinite(v))o.Add("value",static_cast<double>(v));else o.Add("value",std::isnan(v)?"NaN":v>0?"Infinity":"-Infinity");}
    else if constexpr(std::is_integral_v<T> && sizeof(T)>4)o.Add("value",std::to_string(v));
    else o.Add("value",v);
    return o;
}
inline CPLJSONObject allocated(char* p) {auto out=value(p);CPLFree(p);return out;}
template<class T> CPLJSONObject numbers(const T* p,size_t n) {CPLJSONArray a;for(size_t i=0;i<n;i++)a.Add(value(p[i])["value"]);return value(a);}
inline CPLJSONObject numbers(std::initializer_list<double> v) {return numbers(v.begin(),v.size());}
inline CPLJSONObject strings(char** p) {CPLJSONArray a;for(int i=0;p&&p[i];i++)a.Add(p[i]);return value(a);}
inline std::vector<double> doubles(const CPLJSONArray& a){std::vector<double> out;for(auto v:a)out.push_back(std::stod(v.ToString()));return out;}
inline std::vector<int> ints(const CPLJSONArray& a){std::vector<int> out;for(auto v:a)out.push_back(v.ToInteger());return out;}
struct Strings {
    char** list=nullptr;
    explicit Strings(const CPLJSONArray& a){for(auto v:a){auto s=v.ToString();require(s.find('\0')==std::string::npos,"NUL in GDAL string");list=CSLAddString(list,s.c_str());}}
    ~Strings(){CSLDestroy(list);}
    char** data(){return list;}
};
struct Runtime {
    struct Entry {void* pointer;std::string type;bool owned;unsigned long long parent;};
    std::map<unsigned long long,Entry> handles;
    unsigned long long next=0;
    ~Runtime(){while(!handles.empty())close(handles.rbegin()->first);}
    void* get(unsigned long long id,const std::string& type="") {
        auto it=handles.find(id);require(it!=handles.end(),"GDAL handle is closed");
        require(type.empty()||it->second.type==type,"GDAL handle has the wrong type");
        if(it->second.parent)get(it->second.parent);
        return it->second.pointer;
    }
    void* arg(const CPLJSONObject& v){return get(std::stoull(v.GetString("handle")),v.GetString("type"));}
    CPLJSONObject result(void* p,const char* type,bool owned,unsigned long long parent=0) {
        if(!p)return nullValue();auto id=++next;handles[id]={p,type,owned,parent};return value(std::to_string(id));
    }
    void invalidateChildren(void* parent,const std::string& type) {
        std::vector<unsigned long long> children;
        for(auto& item:handles) {
            auto p=handles.find(item.second.parent);
            if(p!=handles.end() && p->second.pointer==parent && item.second.type==type)children.push_back(item.first);
        }
        for(auto id:children)close(id);
    }
    void invalidateOverviews(void* dataset) {
        std::vector<void*> bands;
        for(auto& item:handles) {
            auto p=handles.find(item.second.parent);
            if(p!=handles.end() && p->second.pointer==dataset && item.second.type=="Band")bands.push_back(item.second.pointer);
        }
        for(auto band:bands)invalidateChildren(band,"Band");
    }
    void close(unsigned long long id) {
        auto found=handles.find(id);if(found==handles.end())return;
        std::vector<unsigned long long> children;for(auto& item:handles)if(item.second.parent==id)children.push_back(item.first);
        for(auto child:children)close(child);
        auto e=found->second;handles.erase(found);if(!e.owned)return;
        if(e.type=="Dataset")GDALClose(e.pointer);
        else if(e.type=="Feature")OGR_F_Destroy(e.pointer);
        else if(e.type=="Geometry")OGR_G_DestroyGeometry(e.pointer);
        else if(e.type=="SpatialReference")OSRRelease(e.pointer);
        else if(e.type=="CoordinateTransformation")OCTDestroyCoordinateTransformation(e.pointer);
        else if(e.type=="FeatureDefn")OGR_FD_Release(e.pointer);
        else if(e.type=="FieldDefn")OGR_Fld_Destroy(e.pointer);
        else if(e.type=="GeomFieldDefn")OGR_GFld_Destroy(static_cast<OGRGeomFieldDefnH>(e.pointer));
        else if(e.type=="GCP"){GDALDeinitGCPs(1,static_cast<GDAL_GCP*>(e.pointer));CPLFree(e.pointer);}
        else throw std::runtime_error("Unknown owned GDAL handle type");
    }
    CPLJSONObject execute(const CPLJSONObject& request);
};
struct Arg {
    Runtime& r;CPLJSONObject json;void* pointer=nullptr;bool native=false;
    mutable std::unique_ptr<Strings> list;
    Arg(Runtime& r,const CPLJSONObject& json):r(r),json(json){}
    Arg(Runtime& r,void* p):r(r),pointer(p),native(true){}
    Arg(Runtime& r,int n):r(r),json(value(n)["value"]){}
    template<class T> operator T() const {
        if constexpr(std::is_pointer_v<T> && !std::is_same_v<T,const char*> && !std::is_same_v<T,char**> && !std::is_same_v<T,const char* const*>)return static_cast<T>(native?pointer:r.arg(json));
        else if constexpr(std::is_same_v<T,const char*>){text=json.ToString();require(text.find('\0')==std::string::npos,"NUL in GDAL string");return text.c_str();}
        else if constexpr(std::is_same_v<T,char**>||std::is_same_v<T,const char* const*>){list=std::make_unique<Strings>(json.ToArray());return list->data();}
        else if constexpr(std::is_enum_v<T>)return static_cast<T>(json.ToInteger());
        else if constexpr(std::is_floating_point_v<T>)return static_cast<T>(std::stod(json.ToString()));
        else if constexpr(std::is_integral_v<T>)return static_cast<T>(json.GetType()==CPLJSONObject::Type::Boolean?json.ToBool():json.ToLong());
        else {static_assert(!sizeof(T),"An explicit GDAL mapping is required for this argument type");}
    }
    mutable std::string text;
};
template<class R,class... P,class... A> R apply(R(*fn)(P...),const A&... a){static_assert(sizeof...(P)==sizeof...(A),"Incorrect GDAL arity");return fn(static_cast<P>(a)...);}

template<class T> CPLJSONObject rasterTyped(GDALRasterBandH h,const CPLJSONObject& a,bool write,GDALDataType type){
    auto json=a.GetArray("buffer");int x=a.GetInteger("xOffset"),y=a.GetInteger("yOffset");
    int width=a["xSize"].IsValid()?a.GetInteger("xSize"):GDALGetRasterBandXSize(h);
    int height=a["ySize"].IsValid()?a.GetInteger("ySize"):GDALGetRasterBandYSize(h);
    require(x>=0&&y>=0&&width>0&&height>0&&x<=GDALGetRasterBandXSize(h)-width&&y<=GDALGetRasterBandYSize(h)-height,"Raster window is outside the band");
    // size_t is 32-bit in Wasm and Android x86/ARMv7. Validate before narrowing
    // so a huge virtual raster cannot wrap into a small allocation passed to GDAL.
    uint64_t count=static_cast<uint64_t>(width)*height;require(count<=static_cast<uint64_t>(json.Size()),"Raster buffer is too small");
    std::vector<T> data(count);
    if(write)for(size_t i=0;i<count;i++){
        if constexpr(std::is_floating_point_v<T>)data[i]=static_cast<T>(std::stod(json[static_cast<int>(i)].ToString()));
        else data[i]=static_cast<T>(std::stoll(json[static_cast<int>(i)].ToString()));
    }
    auto code=GDALRasterIO(h,write?GF_Write:GF_Read,x,y,width,height,data.data(),width,height,type,0,0);
    CPLJSONObject out;out.Add("code",code);if(!write)out.Add("buffer",numbers(data.data(),data.size())["value"]);CPLJSONObject result;result.Add("value",out);return result;
}
inline CPLJSONObject raster(GDALRasterBandH h,const CPLJSONObject& a,bool write,const std::string& method){
    if(method.find("_ByteArray")!=std::string::npos)return rasterTyped<unsigned char>(h,a,write,GDT_Byte);
    if(method.find("_ShortArray")!=std::string::npos)return rasterTyped<short>(h,a,write,GDT_Int16);
    if(method.find("_IntArray")!=std::string::npos)return rasterTyped<int>(h,a,write,GDT_Int32);
    if(method.find("_LongArray")!=std::string::npos)return rasterTyped<int64_t>(h,a,write,GDT_Int64);
    if(method.find("_FloatArray")!=std::string::npos)return rasterTyped<float>(h,a,write,GDT_Float32);
    return rasterTyped<double>(h,a,write,GDT_Float64);
}
inline CPLJSONObject Runtime::execute(const CPLJSONObject& request){
    Runtime& r=*this;const auto type=request.GetString("type"),method=request.GetString("method");
    auto id=std::stoull(request.GetString("handle","0"));
    if(method=="close"){close(id);return nullValue();}
    auto a=request.GetObj("parameters");void* h=type=="GdalRuntime"?nullptr:get(id,type);
    // A mutation can replace native children while their Kotlin wrappers still exist.
    // Invalidate aliases after arguments have been resolved and the C call has finished.
    std::string invalidated;
    if(type=="Feature" && (method.rfind("setGeometry",0)==0 || method.rfind("setGeomField",0)==0))invalidated="Geometry";
    if(type=="Geometry" && (method=="empty" || method.rfind("removeGeometry",0)==0))invalidated="Geometry";
    if((type=="Geometry" && method.rfind("assignSpatialReference",0)==0) || (type=="GeomFieldDefn" && method.rfind("setSpatialRef",0)==0) || (type=="Dataset" && method.rfind("setGCPs",0)==0))invalidated="SpatialReference";
    if(type=="FeatureDefn" && method.rfind("setGeomType",0)==0)invalidated="GeomFieldDefn";
    if(type=="Band" && method.rfind("createMaskBand",0)==0)invalidated="Band";
    struct InvalidateAfter {
        Runtime& runtime;void* pointer;std::string type;bool overviews;
        ~InvalidateAfter(){if(!type.empty())runtime.invalidateChildren(pointer,type);if(overviews)runtime.invalidateOverviews(pointer);}
    } invalidate{r,h,invalidated,type=="Dataset" && method.rfind("buildOverviews",0)==0};
#include "runtime.generated.inc"
    throw std::runtime_error("Unknown GDAL runtime operation: "+type+"."+method);
}
}
