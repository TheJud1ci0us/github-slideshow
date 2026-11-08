// JNI bridge between Kotlin (com.ascolta.app.whisper.WhisperLib) and whisper.cpp.
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <mutex>
#include <string>

#include "ggml-backend.h"
#include "whisper.h"

#define TAG "AscoltaWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

struct Session {
    whisper_context *ctx = nullptr;
    std::atomic<bool> abort{false};
};

struct ListenerData {
    JNIEnv *env;
    jobject listener;
    jmethodID onProgress;
    jmethodID onSegment;
};

std::once_flag backendsLoaded;

jstring utf8String(JNIEnv *env, const char *text);

void progressCallback(whisper_context *, whisper_state *, int progress, void *userData) {
    auto *data = static_cast<ListenerData *>(userData);
    if (data == nullptr || data->listener == nullptr) return;
    data->env->CallVoidMethod(data->listener, data->onProgress, (jint) progress);
    if (data->env->ExceptionCheck()) data->env->ExceptionClear();
}

/** Called by whisper.cpp as soon as new sentences are decoded, so they can be shown right away. */
void newSegmentCallback(whisper_context *, whisper_state *state, int nNew, void *userData) {
    auto *data = static_cast<ListenerData *>(userData);
    if (data == nullptr || data->listener == nullptr || data->onSegment == nullptr) return;
    JNIEnv *env = data->env;
    const int n = whisper_full_n_segments_from_state(state);
    for (int i = n - nNew; i < n; i++) {
        if (i < 0) continue;
        jlong t0 = (jlong) whisper_full_get_segment_t0_from_state(state, i) * 10; // centiseconds -> ms
        jlong t1 = (jlong) whisper_full_get_segment_t1_from_state(state, i) * 10;
        jstring text = utf8String(env, whisper_full_get_segment_text_from_state(state, i));
        env->CallVoidMethod(data->listener, data->onSegment, t0, t1, text);
        env->DeleteLocalRef(text);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
}

bool abortCallback(void *userData) {
    return static_cast<Session *>(userData)->abort.load();
}

bool encoderBeginCallback(whisper_context *, whisper_state *, void *userData) {
    // Returning false stops before starting the next 30 s window.
    return !static_cast<Session *>(userData)->abort.load();
}

std::string toStdString(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

/** whisper.cpp returns UTF-8; NewStringUTF expects "modified UTF-8", so go through bytes. */
jstring utf8String(JNIEnv *env, const char *text) {
    jsize len = (jsize) strlen(text);
    jbyteArray bytes = env->NewByteArray(len);
    env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte *>(text));
    jclass strClass = env->FindClass("java/lang/String");
    jmethodID strCtor = env->GetMethodID(strClass, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = (jstring) env->NewObject(strClass, strCtor, bytes, charset);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(strClass);
    env->DeleteLocalRef(bytes);
    return result;
}

whisper_vad_params vadParams() {
    whisper_vad_params p = whisper_vad_default_params();
    p.threshold = 0.5f;
    p.min_speech_duration_ms = 250;
    p.min_silence_duration_ms = 500;   // keep sentences together across short pauses
    p.max_speech_duration_s = 25.0f;   // keep segments short enough to read and translate
    p.speech_pad_ms = 200;
    return p;
}

} // namespace

/**
 * Loads the ggml CPU plug-ins from the app's native library folder; ggml picks the
 * variant best suited to this phone's CPU (e.g. with dot-product / int8 matmul support).
 */
extern "C" JNIEXPORT void JNICALL
Java_com_ascolta_app_whisper_WhisperLib_nativeLoadBackends(JNIEnv *env, jclass, jstring libDir) {
    std::string dir = toStdString(env, libDir);
    std::call_once(backendsLoaded, [&dir]() {
        ggml_backend_load_all_from_path(dir.c_str());
        LOGI("Loaded %zu ggml backends from %s", ggml_backend_reg_count(), dir.c_str());
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ascolta_app_whisper_WhisperLib_nativeCpuVariant(JNIEnv *env, jclass) {
    std::string out;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        ggml_backend_reg_t reg = ggml_backend_reg_get(i);
        if (!out.empty()) out += ", ";
        out += ggml_backend_reg_name(reg);
        auto getFeatures = (ggml_backend_get_features_t) ggml_backend_reg_get_proc_address(reg, "ggml_backend_get_features");
        if (getFeatures != nullptr) {
            std::string feats;
            for (ggml_backend_feature *f = getFeatures(reg); f != nullptr && f->name != nullptr; f++) {
                if (strcmp(f->value, "1") == 0) {
                    if (!feats.empty()) feats += " ";
                    feats += f->name;
                } else if (strcmp(f->name, "VARIANT") == 0 || strcmp(f->name, "CPU_VARIANT") == 0) {
                    feats = std::string(f->value) + (feats.empty() ? "" : " ") + feats;
                }
            }
            if (!feats.empty()) out += " (" + feats + ")";
        }
    }
    return utf8String(env, out.empty() ? "no backend loaded" : out.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ascolta_app_whisper_WhisperLib_nativeInit(JNIEnv *env, jclass, jstring modelPath, jboolean flashAttention) {
    std::string path = toStdString(env, modelPath);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    cparams.flash_attn = flashAttention;
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) {
        LOGW("Failed to load model %s", path.c_str());
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
    return utf8String(env, whisper_print_system_info());
}

/**
 * Transcribes 16 kHz mono float samples. Returns an array of WhisperSegment
 * (timestamps relative to the start of [samples]), or null if aborted / failed.
 * With [vadModelPath], only the parts containing speech are processed
 * (music and silence are skipped); timestamps still refer to the original audio.
 */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ascolta_app_whisper_WhisperLib_nativeTranscribe(
        JNIEnv *env, jclass, jlong handle, jfloatArray samples, jstring language,
        jint threads, jstring prompt, jobject listener, jstring vadModelPath) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return nullptr;

    std::string lang = toStdString(env, language);
    std::string promptText = toStdString(env, prompt);
    std::string vadPath = toStdString(env, vadModelPath);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = lang.c_str();
    params.n_threads = threads;
    params.no_context = true;
    params.suppress_blank = true;
    params.suppress_nst = true;
    if (!promptText.empty()) params.initial_prompt = promptText.c_str();
    if (!vadPath.empty()) {
        params.vad = true;
        params.vad_model_path = vadPath.c_str();
        params.vad_params = vadParams();
    }

    ListenerData callbacks{env, listener, nullptr, nullptr};
    if (listener != nullptr) {
        jclass cls = env->GetObjectClass(listener);
        callbacks.onProgress = env->GetMethodID(cls, "onProgress", "(I)V");
        callbacks.onSegment = env->GetMethodID(cls, "onSegment", "(JJLjava/lang/String;)V");
        env->DeleteLocalRef(cls);
        params.progress_callback = progressCallback;
        params.progress_callback_user_data = &callbacks;
        params.new_segment_callback = newSegmentCallback;
        params.new_segment_callback_user_data = &callbacks;
    }
    params.abort_callback = abortCallback;
    params.abort_callback_user_data = session;
    params.encoder_begin_callback = encoderBeginCallback;
    params.encoder_begin_callback_user_data = session;

    jsize count = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    int rc = whisper_full(session->ctx, params, data, count);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);

    if (rc != 0 || session->abort.load()) {
        LOGI("whisper_full stopped (rc=%d, abort=%d)", rc, (int) session->abort.load());
        return nullptr;
    }

    jclass segClass = env->FindClass("com/ascolta/app/whisper/WhisperSegment");
    jmethodID ctor = env->GetMethodID(segClass, "<init>", "(JJLjava/lang/String;)V");
    const int n = whisper_full_n_segments(session->ctx);
    jobjectArray result = env->NewObjectArray(n, segClass, nullptr);
    for (int i = 0; i < n; i++) {
        jlong t0 = (jlong) whisper_full_get_segment_t0(session->ctx, i) * 10; // centiseconds -> ms
        jlong t1 = (jlong) whisper_full_get_segment_t1(session->ctx, i) * 10;
        jstring text = utf8String(env, whisper_full_get_segment_text(session->ctx, i));
        jobject seg = env->NewObject(segClass, ctor, t0, t1, text);
        env->SetObjectArrayElement(result, i, seg);
        env->DeleteLocalRef(seg);
        env->DeleteLocalRef(text);
    }
    return result;
}

/**
 * Finds the speech in 16 kHz mono audio with the Silero voice-activity model.
 * Returns [start0, end0, start1, end1, …] in milliseconds, or null on failure.
 */
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_ascolta_app_whisper_WhisperLib_nativeDetectSpeech(
        JNIEnv *env, jclass, jstring vadModelPath, jfloatArray samples, jint threads) {
    std::string path = toStdString(env, vadModelPath);
    whisper_vad_context_params cparams = whisper_vad_default_context_params();
    cparams.n_threads = threads;
    cparams.use_gpu = false;
    whisper_vad_context *vctx = whisper_vad_init_from_file_with_params(path.c_str(), cparams);
    if (vctx == nullptr) {
        LOGW("Failed to load VAD model %s", path.c_str());
        return nullptr;
    }
    jsize count = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    whisper_vad_segments *segments = whisper_vad_segments_from_samples(vctx, vadParams(), data, count);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);

    jlongArray result = nullptr;
    if (segments != nullptr) {
        const int n = whisper_vad_segments_n_segments(segments);
        result = env->NewLongArray(n * 2);
        jlong *out = env->GetLongArrayElements(result, nullptr);
        for (int i = 0; i < n; i++) {
            out[2 * i] = (jlong) (whisper_vad_segments_get_segment_t0(segments, i) * 10.0f);
            out[2 * i + 1] = (jlong) (whisper_vad_segments_get_segment_t1(segments, i) * 10.0f);
        }
        env->ReleaseLongArrayElements(result, out, 0);
        whisper_vad_free_segments(segments);
    }
    whisper_vad_free(vctx);
    return result;
}
