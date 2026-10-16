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

namespace {
std::atomic<unsigned long long> sequence{0};
std::once_flag registered;
// GDAL keeps PROJ search paths globally. Serialize path changes and restore the host state.
std::mutex configurationMutex;
void ensure(bool ok, const std::string &message) {
    if (!ok) throw std::runtime_error(message + ": " + CPLGetLastErrorMsg());
}
void basename(const std::string &s) {
    ensure(!s.empty() && s != "." && s != ".." && s.find_first_of("/\\:") == std::string::npos && s.find('\0') == std::string::npos, "Invalid file basename");
}
struct Directory {
    std::string path = "/vsimem/gdal4k-" + std::to_string(++sequence);
    Directory() { ensure(VSIMkdir(path.c_str(), 0700) == 0, "Cannot create GDAL workspace"); }
    ~Directory() { VSIRmdirRecursive(path.c_str()); }
};
struct Dataset {
    Directory directory;
    GDALDatasetH handle = nullptr;
    ~Dataset() { if (handle) GDALClose(handle); }
};
struct Options {
    char **list = nullptr;
    explicit Options(const CPLJSONArray &array) { for (const auto &s : array) add(s.ToString()); }
    void add(const std::string &s) { ensure(s.find('\0') == std::string::npos, "NUL in GDAL argument"); list = CSLAddString(list, s.c_str()); }
    ~Options() { CSLDestroy(list); }
};
CPLJSONObject parse(const char *value) {
    CPLJSONDocument doc;
    ensure(value && doc.LoadMemory(value), "Invalid GDAL JSON response");
    return doc.GetRoot();
}
CPLJSONObject outputFiles(const Directory &dir, const std::string &main) {
    CPLJSONArray files;
    char **names = VSIReadDir(dir.path.c_str());
    try {
        bool found = false;
        for (int i = 0; names && names[i]; ++i) {
            const std::string name(names[i]);
            if (name == "." || name == "..") continue;
            basename(name);
            vsi_l_offset size = 0;
            auto data = VSIGetMemFileBuffer((dir.path + "/" + name).c_str(), &size, FALSE);
            ensure(data != nullptr, "Output must be a flat set of files");
            ensure(size <= static_cast<vsi_l_offset>(INT_MAX), "Output file exceeds bridge size limit");
            char *encoded = CPLBase64Encode(static_cast<int>(size), data);
            ensure(encoded != nullptr, "Cannot encode output file");
            CPLJSONObject file;
            file.Add("name", name); file.Add("data", encoded); CPLFree(encoded);
            files.Add(file);
            found = found || name == main;
        }
        ensure(found, "GDAL did not create the main output file");
    } catch (...) { CSLDestroy(names); throw; }
    CSLDestroy(names);
    CPLJSONObject result;
    result.Add("main", main); result.Add("files", files);
    return result;
}
}

struct gdal4k_context {
    std::map<std::string, std::unique_ptr<Dataset>> datasets;
    std::mutex mutex;
    unsigned long long nextId = 0;
    std::string projData, gdalData;
};

static CPLJSONObject execute(gdal4k_context &ctx, const CPLJSONObject &request) {
    const auto op = request.GetString("op");
    const auto args = request.GetObj("args");
    if (op == "drivers") {
        CPLJSONArray result;
        for (int i = 0; i < GDALGetDriverCount(); ++i) {
            auto d = GDALGetDriver(i);
            CPLJSONObject item;
            item.Add("name", GDALGetDriverShortName(d)); item.Add("longName", GDALGetDriverLongName(d));
            item.Add("raster", GDALGetMetadataItem(d, GDAL_DCAP_RASTER, nullptr) != nullptr);
            item.Add("vector", GDALGetMetadataItem(d, GDAL_DCAP_VECTOR, nullptr) != nullptr);
            result.Add(item);
        }
        return result;
    }
    if (op == "transform") {
        auto source = OSRNewSpatialReference(nullptr), target = OSRNewSpatialReference(nullptr);
        auto cleanup = [&] { OSRDestroySpatialReference(source); OSRDestroySpatialReference(target); };
        OGRCoordinateTransformationH transform = nullptr;
        try {
            ensure(OSRSetFromUserInput(source, args.GetString("source").c_str()) == OGRERR_NONE, "Invalid source CRS");
            ensure(OSRSetFromUserInput(target, args.GetString("target").c_str()) == OGRERR_NONE, "Invalid target CRS");
            OSRSetAxisMappingStrategy(source, OAMS_TRADITIONAL_GIS_ORDER);
            OSRSetAxisMappingStrategy(target, OAMS_TRADITIONAL_GIS_ORDER);
            transform = OCTNewCoordinateTransformation(source, target);
            ensure(transform != nullptr, "Cannot create coordinate transformation");
            CPLJSONArray result;
            for (const auto &p : args.GetArray("points")) {
                const auto point = p.ToArray();
                double x = point[0].ToDouble(), y = point[1].ToDouble(), z = point[2].ToDouble();
                ensure(OCTTransform(transform, 1, &x, &y, &z) != 0 && std::isfinite(x) && std::isfinite(y) && std::isfinite(z), "Coordinate transformation failed");
                CPLJSONArray out; out.Add(x); out.Add(y); out.Add(z); result.Add(out);
            }
            OCTDestroyCoordinateTransformation(transform); cleanup();
            return result;
        } catch (...) { if (transform) OCTDestroyCoordinateTransformation(transform); cleanup(); throw; }
    }
    if (op == "open") {
        auto dataset = std::make_unique<Dataset>();
        const auto main = args.GetString("main"); basename(main);
        for (const auto &file : args.GetArray("files")) {
            const auto name = file.GetString("name"); basename(name);
            const auto base64 = file.GetString("data");
            std::vector<GByte> data(base64.begin(), base64.end()); data.push_back(0);
            int count = CPLBase64DecodeInPlace(data.data());
            const auto path = dataset->directory.path + "/" + name;
            auto f = VSIFOpenL(path.c_str(), "wb"); ensure(f != nullptr, "Cannot create input file");
            auto written = VSIFWriteL(data.data(), 1, count, f);
            const auto closed = VSIFCloseL(f);
            ensure(written == static_cast<size_t>(count) && closed == 0, "Cannot write input file");
        }
        Options options(args.GetArray("options"));
        dataset->handle = GDALOpenEx((dataset->directory.path + "/" + main).c_str(), GDAL_OF_READONLY | GDAL_OF_VERBOSE_ERROR, nullptr, options.list, nullptr);
        ensure(dataset->handle != nullptr, "Cannot open dataset");
        CPLJSONObject result;
        const auto id = std::to_string(++ctx.nextId);
        result.Add("id", id); result.Add("driver", GDALGetDriverShortName(GDALGetDatasetDriver(dataset->handle)));
        result.Add("width", GDALGetRasterXSize(dataset->handle)); result.Add("height", GDALGetRasterYSize(dataset->handle));
        result.Add("bands", GDALGetRasterCount(dataset->handle)); result.Add("layers", GDALDatasetGetLayerCount(dataset->handle));
        ctx.datasets[id] = std::move(dataset);
        return result;
    }
    const auto id = args.GetString("id");
    const auto found = ctx.datasets.find(id);
    ensure(found != ctx.datasets.end(), "Dataset is closed or belongs to another session");
    auto ds = found->second->handle;
    if (op == "close") { ctx.datasets.erase(found); return CPLJSONObject(); }
    if (op == "info") {
        Options options(args.GetArray("options"));
        options.add("-json");
        char *text = nullptr;
        if (GDALGetRasterCount(ds) > 0) {
            auto opts = GDALInfoOptionsNew(options.list, nullptr); ensure(opts != nullptr, "Invalid raster info options");
            text = GDALInfo(ds, opts); GDALInfoOptionsFree(opts);
        } else {
            options.add("-so"); options.add("-al");
            auto opts = GDALVectorInfoOptionsNew(options.list, nullptr); ensure(opts != nullptr, "Invalid vector info options");
            text = GDALVectorInfo(ds, opts); GDALVectorInfoOptionsFree(opts);
        }
        ensure(text != nullptr, "Cannot inspect dataset");
        try { auto result = parse(text); CPLFree(text); return result; } catch (...) { CPLFree(text); throw; }
    }
    ensure(op == "translate" || op == "warp" || op == "vector", "Unknown GDAL operation");
    Directory output;
    const auto name = args.GetString("name"); basename(name);
    const auto path = output.path + "/" + name;
    Options options(args.GetArray("options"));
    options.add(op == "vector" ? "-f" : "-of"); options.add(args.GetString("driver"));
    GDALDatasetH result = nullptr;
    int usageError = 0;
    if (op == "translate") {
        auto opts = GDALTranslateOptionsNew(options.list, nullptr); ensure(opts != nullptr, "Invalid translate options");
        result = GDALTranslate(path.c_str(), ds, opts, &usageError); GDALTranslateOptionsFree(opts);
    } else if (op == "warp") {
        auto opts = GDALWarpAppOptionsNew(options.list, nullptr); ensure(opts != nullptr, "Invalid warp options");
        result = GDALWarp(path.c_str(), nullptr, 1, &ds, opts, &usageError); GDALWarpAppOptionsFree(opts);
    } else {
        auto opts = GDALVectorTranslateOptionsNew(options.list, nullptr); ensure(opts != nullptr, "Invalid vector translate options");
        result = GDALVectorTranslate(path.c_str(), nullptr, 1, &ds, opts, &usageError); GDALVectorTranslateOptionsFree(opts);
    }
    if (result) GDALClose(result);
    ensure(result != nullptr && !usageError && CPLGetLastErrorType() < CE_Failure, "GDAL conversion failed");
    return outputFiles(output, name);
}

extern "C" gdal4k_context *gdal4k_create(const char *proj, const char *data) {
    try {
        std::call_once(registered, [] { GDALAllRegister(); });
        auto ctx = new gdal4k_context;
        if (proj) ctx->projData = proj;
        if (data) ctx->gdalData = data;
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
