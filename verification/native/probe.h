#ifdef __cplusplus
extern "C" {
#endif
#if defined(_WIN32) && defined(GDAL4K_PROBE_BUILD)
#define GDAL4K_PROBE_API __declspec(dllexport)
#elif defined(_WIN32)
#define GDAL4K_PROBE_API __declspec(dllimport)
#else
#define GDAL4K_PROBE_API
#endif
GDAL4K_PROBE_API int gdal4k_probe(void);
#ifdef __cplusplus
}
#endif
