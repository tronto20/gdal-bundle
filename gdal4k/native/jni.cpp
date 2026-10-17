#include "gdal4k.h"
#include <jni.h>
#if defined(_WIN32)
#undef JNIEXPORT
#define JNIEXPORT __declspec(dllexport)
#endif
#include <string>
static std::string bytes(JNIEnv *env, jbyteArray input) {
    const auto n = env->GetArrayLength(input);
    std::string value(n, '\0');
    env->GetByteArrayRegion(input, 0, n, reinterpret_cast<jbyte *>(value.data()));
    return value;
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_gdal4k_JniBridge_create(JNIEnv *env, jobject, jbyteArray proj, jbyteArray data) {
    return reinterpret_cast<jlong>(gdal4k_create(bytes(env, proj).c_str(), bytes(env, data).c_str()));
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_gdal4k_JniBridge_execute(JNIEnv *env, jobject, jlong handle, jbyteArray request) {
    char *result = gdal4k_execute(reinterpret_cast<gdal4k_context *>(handle), bytes(env, request).c_str());
    if (!result) { env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "GDAL bridge allocation failed"); return nullptr; }
    const std::string value(result);
    gdal4k_free(result);
    auto output = env->NewByteArray(static_cast<jsize>(value.size()));
    if (output) env->SetByteArrayRegion(output, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte *>(value.data()));
    return output;
}
extern "C" JNIEXPORT void JNICALL Java_dev_gdal4k_JniBridge_destroy(JNIEnv *, jobject, jlong handle) { gdal4k_destroy(reinterpret_cast<gdal4k_context *>(handle)); }
