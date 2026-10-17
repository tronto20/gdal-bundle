// Web-only VSI adapter: GDAL seeks and reads through the Worker's bounded input cache.
#include <emscripten.h>
#include <cpl_vsi.h>
#include <cpl_conv.h>
#include <cerrno>
#include <climits>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>

EM_JS(double, web_input_size, (const char* path), {
    return Module.gdal4kInputs.size(UTF8ToString(path));
});
EM_ASYNC_JS(int, web_input_read, (const char* path, double offset, char* output, int length), {
    try {
        const bytes = await Module.gdal4kInputs.read(UTF8ToString(path), offset, length);
        HEAPU8.set(bytes, output);
        return bytes.length;
    } catch (error) {
        Module.gdal4kInputs.lastError = error;
        return -1;
    }
});
namespace {
struct Input {std::string path; vsi_l_offset position = 0; bool eof = false, error = false;};
void* openInput(void*, const char* path, const char* mode) {
    if (!mode || mode[0] != 'r' || std::strchr(mode, '+')) {errno = EACCES; return nullptr;}
    if (web_input_size(path) < 0) {errno = ENOENT; return nullptr;}
    return new Input{path};
}
int statInput(void*, const char* path, VSIStatBufL* stat, int) {
    const double size = web_input_size(path);
    if (size < 0) {errno = ENOENT; return -1;}
    std::memset(stat, 0, sizeof(*stat)); stat->st_mode = S_IFREG | 0444; stat->st_size = static_cast<vsi_l_offset>(size);
    return 0;
}
int seekInput(void* handle, vsi_l_offset offset, int whence) {
    auto& input = *static_cast<Input*>(handle);
    // GDAL encodes negative relative seeks in the unsigned offset type.
    const auto delta = static_cast<int64_t>(offset);
    const auto base = whence == SEEK_SET ? 0 : whence == SEEK_CUR ? static_cast<int64_t>(input.position) : whence == SEEK_END ? static_cast<int64_t>(web_input_size(input.path.c_str())) : -1;
    if (base < 0 || delta < -base || (delta > 0 && delta > 9007199254740991LL - base)) {errno = EINVAL; return -1;}
    input.position = static_cast<vsi_l_offset>(base + delta); input.eof = false; return 0;
}
size_t readInput(void* handle, void* buffer, size_t size, size_t count) {
    auto& input = *static_cast<Input*>(handle);
    if (!size || !count) return 0;
    if (count > static_cast<size_t>(INT_MAX) / size) {errno = EOVERFLOW; input.error = true; return 0;}
    const size_t requested = size * count;
    const int bytes = web_input_read(input.path.c_str(), static_cast<double>(input.position), static_cast<char*>(buffer), static_cast<int>(requested));
    if (bytes < 0) {errno = EIO; input.error = true; return 0;}
    input.position += bytes; input.eof = static_cast<size_t>(bytes) < requested;
    return static_cast<size_t>(bytes) / size;
}
}
void gdal4k_install_web_inputs() {
    auto* callbacks = VSIAllocFilesystemPluginCallbacksStruct();
    callbacks->open = openInput; callbacks->stat = statInput;
    callbacks->tell = [](void* p) {return static_cast<Input*>(p)->position;};
    callbacks->seek = seekInput; callbacks->read = readInput;
    callbacks->eof = [](void* p) {return static_cast<Input*>(p)->eof ? 1 : 0;};
    callbacks->error = [](void* p) {return static_cast<Input*>(p)->error ? 1 : 0;};
    callbacks->clear_err = [](void* p) {auto& i = *static_cast<Input*>(p); i.error = i.eof = false;};
    callbacks->close = [](void* p) {delete static_cast<Input*>(p); return 0;};
    callbacks->sibling_files = [](void*, const char*) {return static_cast<char**>(CPLCalloc(1, sizeof(char*)));};
    VSIInstallPluginHandler("/vsigdal4k/", callbacks);
    VSIFreeFilesystemPluginCallbacksStruct(callbacks);
}
