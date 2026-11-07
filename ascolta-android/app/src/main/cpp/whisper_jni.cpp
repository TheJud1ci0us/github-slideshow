// JNI bridge between Kotlin (com.ascolta.app.whisper.WhisperLib) and whisper.cpp.
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <string>

#include "whisper.h"

#define TAG "AscoltaWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

struct Session {
    whisper_context *ctx = nullptr;
    std::atomic<bool> abort{false};
};

struct ProgressData {
    JNIEnv *env;
    jobject listener;
    jmethodID onProgress;
};

void progressCallback(whisper_context *, whisper_state *, int progress, void *userData) {
    auto *data = static_cast<ProgressData *>(userData);
    if (data == nullptr || data->listener == nullptr) return;
    data->env->CallVoidMethod(data->listener, data->onProgress, (jint) progress);
    if (data->env->ExceptionCheck()) data->env->ExceptionClear();
}

bool abortCallback(void *userData) {
    return static_cast<Session *>(userData)->abort.load();
}

bool encoderBeginCallback(whisper_context *, whisper_state *, void *userData) {
    // Returning false stops before starting the next 30 s window.
    return !static_cast<Session *>(userData)->abort.load();
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_ascolta_app_whisper_WhisperLib_nativeInit(JNIEnv *env, jclass, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) {
        LOGW("Failed to load model");
        return 0;
    }
    auto *session = new Session();
    session->ctx = ctx;
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ascolta_app_whisper_WhisperLib_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return;
    whisper_free(session->ctx);
    delete session;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ascolta_app_whisper_WhisperLib_nativeAbort(JNIEnv *, jclass, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session != nullptr) session->abort.store(true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ascolta_app_whisper_WhisperLib_nativeSystemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

/**
 * Transcribes 16 kHz mono float samples. Returns an array of WhisperSegment
 * (timestamps relative to the start of [samples]), or null if aborted / failed.
 */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ascolta_app_whisper_WhisperLib_nativeTranscribe(
        JNIEnv *env, jclass, jlong handle, jfloatArray samples, jstring language,
        jint threads, jstring prompt, jobject listener) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return nullptr;

    const char *lang = env->GetStringUTFChars(language, nullptr);
    const char *promptChars = prompt != nullptr ? env->GetStringUTFChars(prompt, nullptr) : nullptr;

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = lang;
    params.n_threads = threads;
    params.no_context = true;
    params.suppress_blank = true;
    params.suppress_nst = true;
    if (promptChars != nullptr && promptChars[0] != '\0') {
        params.initial_prompt = promptChars;
    }

    ProgressData progress{env, listener, nullptr};
    if (listener != nullptr) {
        jclass cls = env->GetObjectClass(listener);
        progress.onProgress = env->GetMethodID(cls, "onProgress", "(I)V");
        env->DeleteLocalRef(cls);
        params.progress_callback = progressCallback;
        params.progress_callback_user_data = &progress;
    }
    params.abort_callback = abortCallback;
    params.abort_callback_user_data = session;
    params.encoder_begin_callback = encoderBeginCallback;
    params.encoder_begin_callback_user_data = session;

    jsize count = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    int rc = whisper_full(session->ctx, params, data, count);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);

    env->ReleaseStringUTFChars(language, lang);
    if (promptChars != nullptr) env->ReleaseStringUTFChars(prompt, promptChars);

    if (rc != 0 || session->abort.load()) {
        LOGI("whisper_full stopped (rc=%d, abort=%d)", rc, (int) session->abort.load());
        return nullptr;
    }

    jclass segClass = env->FindClass("com/ascolta/app/whisper/WhisperSegment");
    jmethodID ctor = env->GetMethodID(segClass, "<init>", "(JJLjava/lang/String;)V");
    const int n = whisper_full_n_segments(session->ctx);
    jobjectArray result = env->NewObjectArray(n, segClass, nullptr);
    for (int i = 0; i < n; i++) {
        const char *text = whisper_full_get_segment_text(session->ctx, i);
        jlong t0 = (jlong) whisper_full_get_segment_t0(session->ctx, i) * 10; // centiseconds -> ms
        jlong t1 = (jlong) whisper_full_get_segment_t1(session->ctx, i) * 10;
        // whisper.cpp output is UTF-8; NewStringUTF expects modified UTF-8, so go through bytes.
        jsize len = (jsize) strlen(text);
        jbyteArray bytes = env->NewByteArray(len);
        env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte *>(text));
        jclass strClass = env->FindClass("java/lang/String");
        jmethodID strCtor = env->GetMethodID(strClass, "<init>", "([BLjava/lang/String;)V");
        jstring charset = env->NewStringUTF("UTF-8");
        auto jtext = (jstring) env->NewObject(strClass, strCtor, bytes, charset);
        jobject seg = env->NewObject(segClass, ctor, t0, t1, jtext);
        env->SetObjectArrayElement(result, i, seg);
        env->DeleteLocalRef(seg);
        env->DeleteLocalRef(jtext);
        env->DeleteLocalRef(charset);
        env->DeleteLocalRef(strClass);
        env->DeleteLocalRef(bytes);
    }
    return result;
}
