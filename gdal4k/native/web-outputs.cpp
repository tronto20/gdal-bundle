#include <emscripten.h>
#include <cpl_vsi.h>
#include <cpl_vsi_virtual.h>
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
int statOutput(void*,const char* path,VSIStatBufL* stat,int){auto size=output_size(path);if(size<0){errno=ENOENT;return -1;}std::memset(stat,0,sizeof(*stat));stat->st_mode=S_IFREG|0666;stat->st_size=static_cast<vsi_l_offset>(size);return 0;}
int seekOutput(void* p,vsi_l_offset offset,int whence){auto& f=*static_cast<Output*>(p);int64_t delta=static_cast<int64_t>(offset);int64_t base=whence==SEEK_SET?0:whence==SEEK_CUR?f.position:whence==SEEK_END?output_handle_size(f.id):-1;
    if(base<0 || delta < -base || (delta>0 && delta>9007199254740991LL-base)){errno=EINVAL;return -1;}f.position=base+delta;f.eof=false;return 0;}
size_t transfer(void* p,void* buffer,size_t size,size_t count,bool write){auto& f=*static_cast<Output*>(p);if(!size||!count)return 0;if(count>static_cast<size_t>(INT_MAX)/size){f.error=true;errno=EOVERFLOW;return 0;}
    auto wanted=size*count;auto n=write?output_write(f.id,f.position,static_cast<const char*>(buffer),wanted):output_read(f.id,f.position,static_cast<char*>(buffer),wanted);
    if(n<0){f.error=true;errno=EIO;return 0;}f.position+=n;if(!write)f.eof=static_cast<size_t>(n)<wanted;return n/size;}
class OutputHandle final : public VSIVirtualHandle {
    Output state;
  public:
    OutputHandle(int id, vsi_l_offset position) : state{id, position} {}
    ~OutputHandle() override { if (state.id) Close(); }
    int Seek(vsi_l_offset offset, int whence) override { return seekOutput(&state, offset, whence); }
    vsi_l_offset Tell() override { return state.position; }
    size_t Read(void* buffer, size_t size, size_t count) override { return transfer(&state, buffer, size, count, false); }
    size_t Write(const void* buffer, size_t size, size_t count) override { return transfer(&state, const_cast<void*>(buffer), size, count, true); }
    void ClearErr() override { state.error = state.eof = false; }
    int Eof() override { return state.eof; }
    int Error() override { return state.error; }
    int Truncate(vsi_l_offset size) override { return output_truncate(state.id, size); }
    int Flush() override { return output_flush(state.id); }
    int Close() override { auto id = state.id; state.id = 0; return id ? output_close(id) : 0; }
};
// GDAL 3.12.4's VSI plugin Unlink calls POSIX unlink instead of its callback.
// Register a native handler so SQLite journals are removed on the actual Node
// filesystem or OPFS, with the same positional access handles as raster output.
class OutputFilesystem final : public VSIFilesystemHandler {
  public:
    VSIVirtualHandleUniquePtr Open(const char* path, const char* mode, bool, CSLConstList) override {
        auto id = output_open(path, mode);
        if (!id) { errno = EIO; return nullptr; }
        auto position = mode[0] == 'a' ? output_handle_size(id) : 0;
        if (position < 0) { output_close(id); errno = EIO; return nullptr; }
        return VSIVirtualHandleUniquePtr(new OutputHandle(id, static_cast<vsi_l_offset>(position)));
    }
    int Stat(const char* path, VSIStatBufL* stat, int flags) override { return statOutput(nullptr, path, stat, flags); }
    int Unlink(const char* path) override { return output_unlink(path); }
};
}
void gdal4k_install_web_outputs(){VSIFileManager::InstallHandler("/vsigdal4k-output/",new OutputFilesystem());}
