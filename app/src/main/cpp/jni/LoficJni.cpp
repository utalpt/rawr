// LOFIC photos of the "1x LOFIC" lens: the shutter asks for the next Long/Short pair of one frame and waits
// for it (imaging/LoficGrab). Xiaomi 17 Ultra, from Anton Tretiakov's (@designation_zero) X17U LOFIC RAW v1.0 spec.
#include <jni.h>

#include <chrono>

#include "imaging/LoficGrab.h"

using rawrcam::imaging::LoficGrab;

extern "C" JNIEXPORT jboolean JNICALL Java_com_rawr_camera_lofic_LoficNative_active(JNIEnv*, jobject) {
    return LoficGrab::instance().active() ? JNI_TRUE : JNI_FALSE;
}

// Rows the Long fills in the viewfinder (0 until known): 3072 with the root module, 2304 without it.
extern "C" JNIEXPORT jint JNICALL Java_com_rawr_camera_lofic_LoficNative_viewfinderRows(JNIEnv*, jobject) {
    return jint(LoficGrab::instance().lastRows());
}

extern "C" JNIEXPORT void JNICALL Java_com_rawr_camera_lofic_LoficNative_request(JNIEnv*, jobject) {
    LoficGrab::instance().request();
}

// [ByteArray long, ByteArray short, IntArray{width, rows, iso, matched}, LongArray{timestampNs, exposureNs},
//  FloatArray neutral] or null when no pair came in time.
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_rawr_camera_lofic_LoficNative_waitPair(JNIEnv* env, jobject,
                                                                                         jint timeoutMs) {
    auto pair = LoficGrab::instance().wait(std::chrono::milliseconds(timeoutMs));
    if (!pair) return nullptr;
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray out = env->NewObjectArray(5, objectClass, nullptr);
    if (!out) return nullptr;
    jbyteArray longs = env->NewByteArray(jsize(pair->longBytes.size()));
    jbyteArray shorts = env->NewByteArray(jsize(pair->shortBytes.size()));
    if (!longs || !shorts) return nullptr;
    env->SetByteArrayRegion(longs, 0, jsize(pair->longBytes.size()),
                            reinterpret_cast<const jbyte*>(pair->longBytes.data()));
    env->SetByteArrayRegion(shorts, 0, jsize(pair->shortBytes.size()),
                            reinterpret_cast<const jbyte*>(pair->shortBytes.data()));
    const jint ints[4] = {jint(pair->width), jint(pair->rows), jint(pair->iso), pair->resultMatched ? 1 : 0};
    jintArray intArray = env->NewIntArray(4);
    env->SetIntArrayRegion(intArray, 0, 4, ints);
    const jlong longsValues[2] = {jlong(pair->timestampNs), jlong(pair->exposureNs)};
    jlongArray longArray = env->NewLongArray(2);
    env->SetLongArrayRegion(longArray, 0, 2, longsValues);
    jfloatArray neutral = env->NewFloatArray(3);
    env->SetFloatArrayRegion(neutral, 0, 3, pair->neutral.data());
    env->SetObjectArrayElement(out, 0, longs);
    env->SetObjectArrayElement(out, 1, shorts);
    env->SetObjectArrayElement(out, 2, intArray);
    env->SetObjectArrayElement(out, 3, longArray);
    env->SetObjectArrayElement(out, 4, neutral);
    return out;
}
