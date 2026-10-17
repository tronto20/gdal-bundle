#include "gdal4k.h"
#include <gdal.h>
#include <gdal_utils.h>
#include <ogr_srs_api.h>
#include <cpl_conv.h>
#include <cpl_json.h>
#include <cpl_string.h>
#include <cpl_vsi.h>
#include <atomic>
#include <cmath>
#include <climits>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include "resources.h"
#include "runtime.hpp"

namespace {
std::once_flag registered;
// GDAL keeps PROJ search paths globally. Serialize path changes and restore the host state.
std::mutex configurationMutex;
CPLJSONObject parse(const char *value) {
    CPLJSONDocument doc;
    g4k::require(value && doc.LoadMemory(value), "Invalid GDAL JSON request");
    return doc.GetRoot();
}
}

struct gdal4k_context {
    g4k::Runtime runtime;
    std::mutex mutex;
    std::string projData, gdalData;
};

static CPLJSONObject execute(gdal4k_context &ctx, const CPLJSONObject &request) {
    g4k::require(request.GetString("op") == "runtime", "Unknown GDAL operation");
    return ctx.runtime.execute(request.GetObj("args"));
}

extern "C" gdal4k_context *gdal4k_create(const char *proj, const char *data) {
    try {
        std::call_once(registered, [] { gdal4k_install_resources(); GDALAllRegister(); });
        auto ctx = new gdal4k_context;
        if (proj) ctx->projData = proj;
        ctx->gdalData = data && *data ? data : gdal4k_data_directory();
        return ctx;
    } catch (...) { return nullptr; }
}
extern "C" char *gdal4k_execute(gdal4k_context *ctx, const char *request) {
    if (!ctx) return CPLStrdup("{\"error\":\"GDAL session is closed\"}");
    std::lock_guard<std::mutex> configLock(configurationMutex);
    std::lock_guard<std::mutex> lock(ctx->mutex);
    // Most options are thread-local, but GDAL PROJ search paths require the explicit OSR API.
    char **previousPaths = ctx->projData.empty() ? nullptr : OSRGetPROJSearchPaths();
    if (!ctx->projData.empty()) {
        const char *paths[] = {ctx->projData.c_str(), nullptr};
        OSRSetPROJSearchPaths(paths);
    }
    char **previous = CPLGetThreadLocalConfigOptions();
    if (!ctx->projData.empty()) CPLSetThreadLocalConfigOption("PROJ_DATA", ctx->projData.c_str());
    if (!ctx->gdalData.empty()) CPLSetThreadLocalConfigOption("GDAL_DATA", ctx->gdalData.c_str());
    CPLErrorReset();
    CPLJSONObject response;
    try { response.Add("result", execute(*ctx, parse(request))); }
    catch (const std::exception &e) { response.Add("error", e.what()); }
    catch (...) { response.Add("error", "Unknown GDAL error"); }
    if (previousPaths) { OSRSetPROJSearchPaths(previousPaths); CSLDestroy(previousPaths); }
    CPLSetThreadLocalConfigOptions(previous);
    CSLDestroy(previous);
    return CPLStrdup(response.Format(CPLJSONObject::PrettyFormat::Plain).c_str());
}
extern "C" void gdal4k_free(char *s) { CPLFree(s); }
extern "C" void gdal4k_destroy(gdal4k_context *ctx) {
    std::lock_guard<std::mutex> lock(configurationMutex);
    delete ctx;
}
