#include <emscripten.h>
#include <cpl_vsi.h>
#include <cerrno>
#include <climits>
#include <cstdint>
#include <cstdio>
#include <cstring>

EM_ASYNC_JS(int, output_open, (const char* path,const char* mode), {return await Module.gdal4kOutputs.open(UTF8ToString(path),UTF8ToString(mode));});
EM_ASYNC_JS(double, output_size, (const char* path), {return await Module.gdal4kOutputs.size(UTF8ToString(path));});
EM_ASYNC_JS(int, output_unlink, (const char* path), {return await Module.gdal4kOutputs.unlink(UTF8ToString(path));});
EM_JS(double, output_handle_size, (int id), {try{return Module.gdal4kOutputs.handles.get(id).handle.size();}catch(e){Module.gdal4kOutputs.lastError=e;return -1;}});
EM_JS(int, output_read, (int id,double offset,char* buffer,int count), {try{return Module.gdal4kOutputs.read(id,HEAPU8.subarray(buffer,buffer+count),offset);}catch(e){Module.gdal4kOutputs.lastError=e;return -1;}});
EM_JS(int, output_write, (int id,double offset,const char* buffer,int count), {try{return Module.gdal4kOutputs.write(id,HEAPU8.subarray(buffer,buffer+count),offset);}catch(e){Module.gdal4kOutputs.lastError=e;return -1;}});
EM_JS(int, output_truncate, (int id,double size), {try{Module.gdal4kOutputs.truncate(id,size);return 0;}catch(e){Module.gdal4kOutputs.lastError=e;return -1;}});
EM_JS(int, output_flush, (int id), {try{Module.gdal4kOutputs.flush(id);return 0;}catch(e){Module.gdal4kOutputs.lastError=e;return -1;}});
EM_JS(int, output_close, (int id), {try{Module.gdal4kOutputs.close(id);return 0;}catch(e){Module.gdal4kOutputs.lastError=e;return -1;}});
namespace {
struct Output {int id;vsi_l_offset position=0;bool eof=false,error=false;};
void* openOutput(void*,const char* path,const char* mode){auto id=output_open(path,mode);if(!id){errno=EIO;return nullptr;}auto p=new Output{id};if(mode[0]=='a')p->position=output_handle_size(id);return p;}
int statOutput(void*,const char* path,VSIStatBufL* stat,int){auto size=output_size(path);if(size<0){errno=ENOENT;return -1;}std::memset(stat,0,sizeof(*stat));stat->st_mode=S_IFREG|0666;stat->st_size=static_cast<vsi_l_offset>(size);return 0;}
int seekOutput(void* p,vsi_l_offset offset,int whence){auto& f=*static_cast<Output*>(p);int64_t delta=static_cast<int64_t>(offset);int64_t base=whence==SEEK_SET?0:whence==SEEK_CUR?f.position:whence==SEEK_END?output_handle_size(f.id):-1;
    if(base<0 || delta < -base || (delta>0 && delta>9007199254740991LL-base)){errno=EINVAL;return -1;}f.position=base+delta;f.eof=false;return 0;}
size_t transfer(void* p,void* buffer,size_t size,size_t count,bool write){auto& f=*static_cast<Output*>(p);if(!size||!count)return 0;if(count>static_cast<size_t>(INT_MAX)/size){f.error=true;errno=EOVERFLOW;return 0;}
    auto wanted=size*count;auto n=write?output_write(f.id,f.position,static_cast<const char*>(buffer),wanted):output_read(f.id,f.position,static_cast<char*>(buffer),wanted);
    if(n<0){f.error=true;errno=EIO;return 0;}f.position+=n;if(!write)f.eof=static_cast<size_t>(n)<wanted;return n/size;}
}
void gdal4k_install_web_outputs(){auto* cb=VSIAllocFilesystemPluginCallbacksStruct();cb->open=openOutput;cb->stat=statOutput;cb->seek=seekOutput;
    cb->tell=[](void* p){return static_cast<Output*>(p)->position;};cb->read=[](void* p,void* b,size_t s,size_t n){return transfer(p,b,s,n,false);};
    cb->write=[](void* p,const void* b,size_t s,size_t n){return transfer(p,const_cast<void*>(b),s,n,true);};
    cb->eof=[](void* p){return static_cast<Output*>(p)->eof?1:0;};cb->error=[](void* p){return static_cast<Output*>(p)->error?1:0;};cb->clear_err=[](void* p){auto& f=*static_cast<Output*>(p);f.error=f.eof=false;};
    cb->truncate=[](void* p,vsi_l_offset n){return output_truncate(static_cast<Output*>(p)->id,n);};
    cb->flush=[](void* p){return output_flush(static_cast<Output*>(p)->id);};cb->close=[](void* p){auto id=static_cast<Output*>(p)->id;delete static_cast<Output*>(p);return output_close(id);};
    cb->unlink=[](void*,const char* path){return output_unlink(path);};VSIInstallPluginHandler("/vsigdal4k-output/",cb);VSIFreeFilesystemPluginCallbacksStruct(cb);}
