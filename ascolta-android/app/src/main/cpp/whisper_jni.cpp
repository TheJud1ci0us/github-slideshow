// JNI bridge between Kotlin (com.ascolta.app.whisper.WhisperLib) and whisper.cpp.
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <mutex>
#include <sched.h>
#include <string>
#include <vector>

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
    whisper_token eot;
};

std::once_flag backendsLoaded;

jstring utf8String(JNIEnv *env, const char *text);

// A failed JNI method lookup leaves NoSuchMethodError pending. Convert it into a
// recoverable exception before returning to the Kotlin worker's error handling.
void callbackLookupFailed(JNIEnv *env, jclass listenerClass) {
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(listenerClass);
    jclass errorClass = env->FindClass("java/lang/IllegalStateException");
    if (errorClass != nullptr) {
        env->ThrowNew(errorClass, "Whisper callbacks unavailable; please update the app");
        env->DeleteLocalRef(errorClass);
    }
}


void progressCallback(whisper_context *, whisper_state *, int progress, void *userData) {
    auto *data = static_cast<ListenerData *>(userData);
    if (data == nullptr || data->listener == nullptr || data->onProgress == nullptr) return;
    data->env->CallVoidMethod(data->listener, data->onProgress, (jint) progress);
    if (data->env->ExceptionCheck()) data->env->ExceptionClear();
}

/**
 * Called by whisper.cpp as soon as new sentences are decoded, so they can be shown right away.
 * Also passes each word's start time (from token timestamps) for word-by-word highlighting.
 * Words are assembled from tokens at byte level, because one UTF-8 character can be split
 * across two tokens.
 */
/** True if [s] doesn't end in the middle of a multi-byte UTF-8 character. */
static bool utf8Complete(const std::string &s) {
    int n = (int) s.size();
    for (int back = 1; back <= 4 && back <= n; back++) {
        auto c = (unsigned char) s[n - back];
        if ((c & 0xC0) == 0x80) continue; // continuation byte
        int len = (c < 0x80) ? 1 : ((c & 0xE0) == 0xC0) ? 2 : ((c & 0xF0) == 0xE0) ? 3 : ((c & 0xF8) == 0xF0) ? 4 : 1;
        return back >= len;
    }
    return true;
}

void newSegmentCallback(whisper_context *ctx, whisper_state *state, int nNew, void *userData) {
    auto *data = static_cast<ListenerData *>(userData);
    if (data == nullptr || data->listener == nullptr || data->onSegment == nullptr) return;
    JNIEnv *env = data->env;
    const int n = whisper_full_n_segments_from_state(state);
    jclass strClass = env->FindClass("java/lang/String");
    for (int i = n - nNew; i < n; i++) {
        if (i < 0) continue;
        jlong t0 = (jlong) whisper_full_get_segment_t0_from_state(state, i) * 10; // centiseconds -> ms
        jlong t1 = (jlong) whisper_full_get_segment_t1_from_state(state, i) * 10;

        std::vector<std::string> words;
        std::vector<jlong> starts;
        std::string pending;
        jlong pendingStart = 0;
        const int nTokens = whisper_full_n_tokens_from_state(state, i);
        for (int j = 0; j < nTokens; j++) {
            whisper_token_data td = whisper_full_get_token_data_from_state(state, i, j);
            if (td.id >= data->eot) continue; // special / timestamp tokens
            const char *piece = whisper_full_get_token_text_from_state(ctx, state, i, j);
            if (piece == nullptr || piece[0] == '\0') continue;
            // One piece per token (the app splits them into real words, also in languages
            // without spaces). A UTF-8 character split across tokens is kept together.
            if (pending.empty()) pendingStart = (jlong) td.t0 * 10;
            pending += piece;
            if (utf8Complete(pending)) {
                words.push_back(pending);
                starts.push_back(pendingStart);
                pending.clear();
            }
        }
        if (!pending.empty()) {
            words.push_back(pending);
            starts.push_back(pendingStart);
        }
        jobjectArray wordArray = env->NewObjectArray((jsize) words.size(), strClass, nullptr);
        jlongArray startArray = env->NewLongArray((jsize) starts.size());
        for (size_t w = 0; w < words.size(); w++) {
            jstring ws = utf8String(env, words[w].c_str());
            env->SetObjectArrayElement(wordArray, (jsize) w, ws);
            env->DeleteLocalRef(ws);
        }
        if (!starts.empty()) env->SetLongArrayRegion(startArray, 0, (jsize) starts.size(), starts.data());

        jstring text = utf8String(env, whisper_full_get_segment_text_from_state(state, i));
        env->CallVoidMethod(data->listener, data->onSegment, t0, t1, text, wordArray, startArray);
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(wordArray);
        env->DeleteLocalRef(startArray);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    env->DeleteLocalRef(strClass);
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
    p.threshold = 0.35f;              // speech over background music often scores below 0.5
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
Java_com_ascolta_app_whisper_WhisperLib_nativeLoadBackends(JNIEnv *env, jclass, jstring libDir, jstring cpuVariant) {
    std::string dir = toStdString(env, libDir);
    std::string variant = cpuVariant != nullptr ? toStdString(env, cpuVariant) : "";
    std::call_once(backendsLoaded, [&dir, &variant]() {
        if (variant.empty()) {
            ggml_backend_load_all_from_path(dir.c_str());
        } else {
            // x86_64 (emulators): ggml's own choice trusts CPUID without checking that the OS
            // enabled those instructions, so the app picks the CPU variant and loads it alone.
            ggml_backend_load((dir + "/" + variant).c_str());
            std::string gpu = dir + "/libggml-opencl.so";
            if (FILE *f = fopen(gpu.c_str(), "rb")) { fclose(f); ggml_backend_load(gpu.c_str()); }
        }
        LOGI("Loaded %zu ggml backends from %s (%s)", ggml_backend_reg_count(), dir.c_str(), variant.empty() ? "auto" : variant.c_str());
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

/** The GPUs ggml can use, e.g. "GPUOpenCL: QUALCOMM Adreno(TM) 732", or "" if none. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_ascolta_app_whisper_WhisperLib_nativeGpuInfo(JNIEnv *env, jclass) {
    std::string out;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (ggml_backend_dev_type(dev) != GGML_BACKEND_DEVICE_TYPE_GPU) continue;
        if (!out.empty()) out += "; ";
        out += std::string(ggml_backend_dev_name(dev)) + ": " + ggml_backend_dev_description(dev);
    }
    return utf8String(env, out.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ascolta_app_whisper_WhisperLib_nativeInit(JNIEnv *env, jclass, jstring modelPath, jboolean flashAttention, jboolean useGpu) {
    std::string path = toStdString(env, modelPath);
    whisper_context_params cparams = whisper_context_default_params();
    // On the GPU (OpenCL), operations it can't run fall back to the CPU automatically.
    cparams.use_gpu = useGpu;
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
        jint threads, jstring prompt, jobject listener, jstring vadModelPath, jboolean fastDecoding) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return nullptr;
    // Each call starts fresh: a previous block may have been abandoned (listener jumped).
    session->abort.store(false);

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
    if (fastDecoding) {
        params.temperature_inc = 0.0f;
        params.greedy.best_of = 1;
    }
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.token_timestamps = true; // per-word times for word highlighting
    if (!promptText.empty()) params.initial_prompt = promptText.c_str();
    if (!vadPath.empty()) {
        params.vad = true;
        params.vad_model_path = vadPath.c_str();
        params.vad_params = vadParams();
    }

    ListenerData callbacks{env, listener, nullptr, nullptr, whisper_token_eot(session->ctx)};
    if (listener != nullptr) {
        jclass cls = env->GetObjectClass(listener);
        callbacks.onProgress = env->GetMethodID(cls, "onProgress", "(I)V");
        if (callbacks.onProgress == nullptr || env->ExceptionCheck()) {
            callbackLookupFailed(env, cls);
            return nullptr; // never call a null method ID
        }
        callbacks.onSegment = env->GetMethodID(
            cls, "onSegment", "(JJLjava/lang/String;[Ljava/lang/String;[J)V");
        if (callbacks.onSegment == nullptr || env->ExceptionCheck()) {
            callbackLookupFailed(env, cls);
            return nullptr;
        }
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
        JNIEnv *env, jclass, jstring vadModelPath, jfloatArray samples, jint threads, jboolean fine) {
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
    whisper_vad_params vp = vadParams();
    if (fine) {
        // Shorter pieces (cut at pauses of 0.25 s, at most 10 s) for models trained on short
        // utterances, which drop words on long input (ReazonSpeech).
        vp.min_silence_duration_ms = 250;
        vp.max_speech_duration_s = 10.0f;
        vp.speech_pad_ms = 120;
    }
    whisper_vad_segments *segments = whisper_vad_segments_from_samples(vctx, vp, data, count);
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

/**
 * Restricts the calling thread to the given CPU cores. Threads it creates afterwards
 * (whisper.cpp's and ONNX Runtime's worker threads) inherit the same cores.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ascolta_app_whisper_WhisperLib_nativeSetThreadAffinity(JNIEnv *env, jclass, jintArray cpus) {
    cpu_set_t set;
    CPU_ZERO(&set);
    jsize n = env->GetArrayLength(cpus);
    jint *ids = env->GetIntArrayElements(cpus, nullptr);
    for (jsize i = 0; i < n; i++) {
        if (ids[i] >= 0 && ids[i] < CPU_SETSIZE) CPU_SET(ids[i], &set);
    }
    env->ReleaseIntArrayElements(cpus, ids, JNI_ABORT);
    if (n == 0) return JNI_FALSE;
    return sched_setaffinity(0, sizeof(set), &set) == 0 ? JNI_TRUE : JNI_FALSE;
}
