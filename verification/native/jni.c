#include <jni.h>
#include "probe.h"
JNIEXPORT jint JNICALL Java_dev_gdal4k_probe_NativeProbe_execute(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return gdal4k_probe();
}
