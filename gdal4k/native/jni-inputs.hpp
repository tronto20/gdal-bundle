// JNI-backed, read-only directories. Only requested files acquire descriptors.
#pragma once
#include <cpl_vsi.h>
#include <cpl_string.h>
#include <cpl_json.h>
#include <map>
#include <memory>
#include <mutex>
#include <atomic>
#include <cstring>
#include <cerrno>

namespace g4k_jni_inputs {
struct Environment {
    JavaVM* vm; JNIEnv* env = nullptr; bool attached = false;
    explicit Environment(JavaVM* value): vm(value) {
        if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
#ifdef __ANDROID__
            attached = vm->AttachCurrentThread(&env, nullptr) == JNI_OK;
#else
            attached = vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) == JNI_OK;
#endif
        }
    }
    ~Environment() { if (attached) vm->DetachCurrentThread(); }
};
struct Directory {
    JavaVM* vm = nullptr; jobject object = nullptr; jmethodID open = nullptr, list = nullptr; jlong context = 0;
    std::mutex mutex; jthrowable failure = nullptr;
    ~Directory() {
        Environment e(vm);
        if (e.env) { e.env->DeleteGlobalRef(object); if (failure) e.env->DeleteGlobalRef(failure); }
    }
    bool capture(JNIEnv* env) {
        if (!env->ExceptionCheck()) return false;
        auto error = env->ExceptionOccurred(); env->ExceptionClear();
        std::lock_guard<std::mutex> lock(mutex);
        if (!failure) failure = static_cast<jthrowable>(env->NewGlobalRef(error));
        env->DeleteLocalRef(error);
        return true;
    }
    std::string invoke(const std::string* name) {
        Environment e(vm); auto* env = e.env;
        if (!env) return {};
        jbyteArray input = nullptr;
        if (name) {
            input = env->NewByteArray(static_cast<jsize>(name->size()));
            if (capture(env) || !input) return {};
            env->SetByteArrayRegion(input, 0, name->size(), reinterpret_cast<const jbyte*>(name->data()));
            if (capture(env)) { env->DeleteLocalRef(input); return {}; }
        }
        auto output = static_cast<jbyteArray>(name ? env->CallObjectMethod(object, open, input) : env->CallObjectMethod(object, list));
        if (input) env->DeleteLocalRef(input);
        if (capture(env) || !output) return {};
        auto result = bytes(env, output); env->DeleteLocalRef(output); capture(env);
        return result;
    }
};
inline std::mutex registryMutex;
inline std::map<std::string, std::shared_ptr<Directory>> registry;
inline std::atomic<unsigned long long> sequence{0};
inline std::pair<std::shared_ptr<Directory>, std::string> lookup(const char* input) {
    std::string path(input);
    const std::string prefix = "/vsigdal4kjni/";
    if (path.compare(0, prefix.size(), prefix) == 0) path.erase(0, prefix.size());
    const auto slash = path.find('/');
    auto id = path.substr(0, slash);
    std::lock_guard<std::mutex> lock(registryMutex);
    auto found = registry.find(id);
    return {found == registry.end() ? nullptr : found->second, slash == std::string::npos ? "" : path.substr(slash + 1)};
}
inline bool validName(const std::string& name) {
    return !name.empty() && name != "." && name != ".." && name.find_first_of("/\\:") == std::string::npos && name.find('\0') == std::string::npos;
}
inline char** names(const std::shared_ptr<Directory>& directory) {
    CPLJSONDocument doc;
    if (!doc.LoadMemory(directory->invoke(nullptr))) return nullptr;
    char** result = nullptr;
    for (const auto& entry: doc.GetRoot().GetArray("names")) result = CSLAddString(result, entry.ToString().c_str());
    return result ? result : static_cast<char**>(CPLCalloc(1, sizeof(char*)));
}
inline void install() {
    static std::once_flag installed;
    std::call_once(installed, [] {
        auto* cb = VSIAllocFilesystemPluginCallbacksStruct();
        cb->open = [](void*, const char* path, const char* mode) -> void* {
            if (!mode || mode[0] != 'r' || std::strchr(mode, '+')) { errno = EACCES; return nullptr; }
            auto [directory, name] = lookup(path);
            if (!directory || !validName(name)) { errno = ENOENT; return nullptr; }
            auto target = directory->invoke(&name);
            if (target.empty()) { errno = ENOENT; return nullptr; }
            return VSIFOpenL(target.c_str(), "rb");
        };
        cb->stat = [](void*, const char* path, VSIStatBufL* info, int flags) {
            auto [directory, name] = lookup(path);
            if (!directory) { errno = ENOENT; return -1; }
            if (name.empty()) { std::memset(info, 0, sizeof(*info)); info->st_mode = S_IFDIR | 0555; return 0; }
            if (!validName(name)) { errno = ENOENT; return -1; }
            auto target = directory->invoke(&name);
            if (target.empty()) { errno = ENOENT; return -1; }
            return VSIStatExL(target.c_str(), info, flags);
        };
        cb->read_dir = [](void*, const char* path, int) -> char** {
            auto [directory, name] = lookup(path);
            return directory && name.empty() ? names(directory) : nullptr;
        };
        cb->sibling_files = [](void*, const char* path) -> char** {
            auto [directory, name] = lookup(path);
            return directory ? names(directory) : nullptr;
        };
        cb->tell = [](void* p) { return VSIFTellL(static_cast<VSILFILE*>(p)); };
        cb->seek = [](void* p, vsi_l_offset o, int w) { return VSIFSeekL(static_cast<VSILFILE*>(p), o, w); };
        cb->read = [](void* p, void* b, size_t s, size_t n) { return VSIFReadL(b, s, n, static_cast<VSILFILE*>(p)); };
        cb->eof = [](void* p) { return VSIFEofL(static_cast<VSILFILE*>(p)); };
        cb->error = [](void* p) { return VSIFErrorL(static_cast<VSILFILE*>(p)); };
        cb->clear_err = [](void* p) { VSIFClearErrL(static_cast<VSILFILE*>(p)); };
        cb->close = [](void* p) { return VSIFCloseL(static_cast<VSILFILE*>(p)); };
        cb->flush = [](void*) { return 0; }; // Read-only directory.
        VSIInstallPluginHandler("/vsigdal4kjni/", cb);
        VSIFreeFilesystemPluginCallbacksStruct(cb);
    });
}
inline jthrowable takeFailure(jlong context) {
    std::lock_guard<std::mutex> lock(registryMutex);
    for (auto& [id, d]: registry) if (d->context == context) {
        std::lock_guard<std::mutex> errorLock(d->mutex);
        if (d->failure) { auto result = d->failure; d->failure = nullptr; return result; }
    }
    return nullptr;
}
}

extern "C" JNIEXPORT jstring JNICALL Java_dev_gdal4k_JniBridge_mount(JNIEnv* env, jobject, jlong context, jobject directory) {
    using namespace g4k_jni_inputs;
    install();
    auto d = std::make_shared<Directory>();
    env->GetJavaVM(&d->vm); d->object = env->NewGlobalRef(directory); d->context = context;
    auto cls = env->GetObjectClass(directory);
    d->open = env->GetMethodID(cls, "openPath", "([B)[B");
    d->list = env->GetMethodID(cls, "listNames", "()[B");
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck() || !d->object) return nullptr;
    auto id = std::to_string(++sequence);
    { std::lock_guard<std::mutex> lock(registryMutex); registry[id] = d; }
    return env->NewStringUTF(("/vsigdal4kjni/" + id + "/").c_str());
}
extern "C" JNIEXPORT void JNICALL Java_dev_gdal4k_JniBridge_unmount(JNIEnv* env, jobject, jstring prefix) {
    const char* value = env->GetStringUTFChars(prefix, nullptr);
    auto [directory, name] = g4k_jni_inputs::lookup(value);
    std::string key(value); env->ReleaseStringUTFChars(prefix, value);
    key = key.substr(std::strlen("/vsigdal4kjni/")); key.resize(key.find('/'));
    std::lock_guard<std::mutex> lock(g4k_jni_inputs::registryMutex);
    g4k_jni_inputs::registry.erase(key);
}
