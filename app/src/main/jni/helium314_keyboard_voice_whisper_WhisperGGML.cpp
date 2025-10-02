#include <string>
#include <vector>
#include <map>
#include <algorithm>
#include <utility>
#include <jni.h>
#include <bits/sysconf.h>
#include "src/ggml/whisper.h"
#include "src/defines.h"
#include "helium314_keyboard_voice_whisper_WhisperGGML.h"
#include "jni_common.h"
#include "src/jni_utils.h"

struct WhisperModelState {
    JNIEnv *env;
    jobject partial_result_instance;
    jmethodID partial_result_method;
    int n_threads = 4;
    struct whisper_context *context = nullptr;

    std::vector<int> last_forbidden_languages;
    std::map<int, std::string> partial_results;

    volatile int cancel_flag = 0;
};

JNIEXPORT jlong JNICALL Java_helium314_keyboard_voice_whisper_WhisperGGML_openNative
  (JNIEnv *env, jobject obj, jstring model_path) {
    std::string model_path_str = jstring2string(env, model_path);

    auto *state = new WhisperModelState();

    AKLOGI("Attempting to load model from file...");
    state->context = whisper_init_from_file_with_params(model_path_str.c_str(), { .use_gpu = false });

    if(!state->context){
        AKLOGE("Failed to initialize whisper_context from path %s", model_path_str.c_str());
        delete state;
        return 0L;
    }

    return reinterpret_cast<jlong>(state);
}

JNIEXPORT jlong JNICALL Java_helium314_keyboard_voice_whisper_WhisperGGML_openFromBufferNative
  (JNIEnv *env, jobject obj, jobject buffer) {
    void* buffer_address = env->GetDirectBufferAddress(buffer);
    jlong buffer_capacity = env->GetDirectBufferCapacity(buffer);

    auto *state = new WhisperModelState();

    AKLOGI("Attempting to load model from buffer...");
    state->context = whisper_init_from_buffer_with_params(buffer_address, buffer_capacity, { .use_gpu = false });

    if(!state->context){
        AKLOGE("Failed to initialize whisper_context from direct buffer");
        delete state;
        return 0L;
    }

    return reinterpret_cast<jlong>(state);
}

JNIEXPORT jstring JNICALL Java_helium314_keyboard_voice_whisper_WhisperGGML_inferNative
  (JNIEnv *env, jobject instance, jlong handle, jfloatArray samples_array, jstring prompt,
   jobjectArray languages, jobjectArray bail_languages, jint decoding_mode, jboolean suppress_non_speech) {

    AKLOGI("[VOICE] ===== Native inferNative() =====");

    auto *state = reinterpret_cast<WhisperModelState *>(handle);
    state->cancel_flag = 0;

    std::vector<int> allowed_languages;
    int num_languages = env->GetArrayLength(languages);
    AKLOGI("[VOICE] Number of languages received: %d", num_languages);

    for (int i=0; i<num_languages; i++) {
        jstring jstr = static_cast<jstring>(env->GetObjectArrayElement(languages, i));
        std::string str = jstring2string(env, jstr);
        int lang_id = whisper_lang_id(str.c_str());

        AKLOGI("[VOICE] Language[%d]: '%s' -> whisper_lang_id=%d", i, str.c_str(), lang_id);
        allowed_languages.push_back(lang_id);
    }

    std::vector<int> forbidden_languages;
    int num_bail_languages = env->GetArrayLength(bail_languages);
    AKLOGI("[VOICE] Number of bail languages: %d", num_bail_languages);

    for (int i=0; i<num_bail_languages; i++) {
        jstring jstr = static_cast<jstring>(env->GetObjectArrayElement(bail_languages, i));
        std::string str = jstring2string(env, jstr);
        int lang_id = whisper_lang_id(str.c_str());

        AKLOGI("[VOICE] Bail language[%d]: '%s' -> whisper_lang_id=%d", i, str.c_str(), lang_id);
        forbidden_languages.push_back(lang_id);
    }

    state->last_forbidden_languages = forbidden_languages;

    size_t num_samples = env->GetArrayLength(samples_array);
    jfloat *samples = env->GetFloatArrayElements(samples_array, nullptr);

    long num_procs = sysconf(_SC_NPROCESSORS_ONLN);
    if(num_procs < 2 || num_procs > 16) num_procs = 6; // Make sure the number is sane

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_realtime = false;
    wparams.print_special = false;
    wparams.print_timestamps = false;
    wparams.max_tokens = 256;
    wparams.n_threads = (int)num_procs;

    wparams.audio_ctx = std::max(160, std::min(1500, (int)ceil((double)num_samples / (double)(320.0)) + 32));
    wparams.temperature_inc = 0.0f;

    // Replicates old tflite behavior
    if(decoding_mode == 0) {
        wparams.strategy = WHISPER_SAMPLING_GREEDY;
        wparams.greedy.best_of = 1;
    } else {
        wparams.strategy = WHISPER_SAMPLING_BEAM_SEARCH;
        wparams.beam_search.beam_size = decoding_mode;
        wparams.greedy.best_of = decoding_mode;
    }


    wparams.suppress_blank = false;
    wparams.suppress_non_speech_tokens = (suppress_non_speech == JNI_TRUE);
    wparams.no_timestamps = num_samples < 16000 * 25;

    // Improved language handling for strict language locking
    AKLOGI("[VOICE] === Configuring language parameters ===");
    if(allowed_languages.size() == 0) {
        // No language specified, use auto-detection
        wparams.language = nullptr;
        AKLOGI("[VOICE] No language specified, using FULL AUTO-DETECTION");
    } else if(allowed_languages.size() == 1) {
        // Single language specified - strict lock to this language
        wparams.language = whisper_lang_str(allowed_languages[0]);
        // Also set allowed_langs to enforce the restriction
        wparams.allowed_langs = allowed_languages.data();
        wparams.allowed_langs_size = allowed_languages.size();
        AKLOGI("[VOICE] SINGLE LANGUAGE - STRICT LOCK");
        AKLOGI("[VOICE]   wparams.language = '%s' (id=%d)", wparams.language, allowed_languages[0]);
        AKLOGI("[VOICE]   wparams.allowed_langs_size = %d", wparams.allowed_langs_size);
    } else {
        // Multiple languages specified - check for priority hint
        bool has_duplicate = (allowed_languages.size() >= 2 &&
                             allowed_languages[0] == allowed_languages[1]);

        if(allowed_languages.size() == 2 && has_duplicate) {
            // Only two languages and they're the same - strict lock
            wparams.language = whisper_lang_str(allowed_languages[0]);
            wparams.allowed_langs = allowed_languages.data();
            wparams.allowed_langs_size = 1;  // Only one language to enforce strict locking
            AKLOGI("[VOICE] DUPLICATE LANGUAGE ONLY - STRICT LOCK ENFORCED");
            AKLOGI("[VOICE]   Language appears twice, enforcing strict lock");
            AKLOGI("[VOICE]   wparams.language = '%s' (id=%d)", wparams.language, allowed_languages[0]);
            AKLOGI("[VOICE]   wparams.allowed_langs_size = 1 (forced)");
        } else if(allowed_languages.size() > 2 && has_duplicate) {
            // Multiple languages with duplicate first - priority hint
            // Set the primary language as strong hint
            wparams.language = whisper_lang_str(allowed_languages[0]);
            wparams.allowed_langs = allowed_languages.data() + 1; // Skip the duplicate
            wparams.allowed_langs_size = allowed_languages.size() - 1;

            AKLOGI("[VOICE] PRIORITIZED MULTI-LANGUAGE - PRIMARY HINT SET");
            AKLOGI("[VOICE]   Primary language (duplicated for priority): '%s' (id=%d)",
                   wparams.language, allowed_languages[0]);
            AKLOGI("[VOICE]   wparams.allowed_langs_size = %d", wparams.allowed_langs_size);
            AKLOGI("[VOICE]   All languages with priority:");
            for(int i = 0; i < allowed_languages.size(); i++) {
                const char* lang_str = whisper_lang_str(allowed_languages[i]);
                AKLOGI("[VOICE]     [%d] '%s' (id=%d) %s",
                       i, lang_str, allowed_languages[i],
                       (i < 2 && has_duplicate) ? "<-- PRIMARY (duplicated)" : "");
            }
        } else {
            // Multiple distinct languages - allow detection between them
            // But still set first language as hint
            wparams.language = whisper_lang_str(allowed_languages[0]);
            wparams.allowed_langs = allowed_languages.data();
            wparams.allowed_langs_size = allowed_languages.size();
            AKLOGI("[VOICE] MULTIPLE DISTINCT LANGUAGES - HINTED AUTO-DETECTION");
            AKLOGI("[VOICE]   Primary hint: '%s' (id=%d)", wparams.language, allowed_languages[0]);
            AKLOGI("[VOICE]   wparams.allowed_langs_size = %d", wparams.allowed_langs_size);
            for(int i = 0; i < wparams.allowed_langs_size; i++) {
                AKLOGI("[VOICE]   Allowed language[%d]: %s (id=%d) %s",
                    i, whisper_lang_str(allowed_languages[i]), allowed_languages[i],
                    i == 0 ? "<-- PRIMARY HINT" : "");
            }
        }
    }

    std::string prompt_str = jstring2string(env, prompt);
    wparams.initial_prompt = prompt_str.c_str();
    AKLOGI("Initial prompt size: %d", prompt_str.size());

    state->env = env;
    state->partial_result_instance = instance;
    state->partial_result_method = env->GetMethodID(
            env->GetObjectClass(instance),
            "invokePartialResult",
            "(Ljava/lang/String;)V");
    
    if (state->partial_result_method == nullptr) {
        AKLOGE("Failed to find invokePartialResult method");
    } else {
        AKLOGI("Successfully found invokePartialResult method");
    }

    wparams.partial_text_callback_user_data = state;
    wparams.partial_text_callback = [](struct whisper_context * ctx, struct whisper_state * state, const whisper_token_data *tokens, size_t n_tokens, void * user_data) {
        AKLOGI("Partial callback invoked with %zu tokens", n_tokens);
        std::string partial;

        //AKLOGI(" -- - - - - -- ");
        for(size_t i=0; i < n_tokens; i++) {
            bool skipping = false;
            if(tokens[i].id == whisper_token_beg(ctx) ||
               tokens[i].id == whisper_token_eot(ctx) ||
               tokens[i].id == whisper_token_nosp(ctx) ||
               tokens[i].id == whisper_token_not(ctx) ||
               tokens[i].id == whisper_token_prev(ctx) ||
               tokens[i].id == whisper_token_solm(ctx) ||
               tokens[i].id == whisper_token_sot(ctx) ||
               tokens[i].id == whisper_token_transcribe(ctx) ||
               tokens[i].id == whisper_token_translate(ctx)) skipping = true;

            // Skip timestamp token
            if(tokens[i].id >= whisper_token_beg(ctx)
                    && tokens[i].id <= whisper_token_beg(ctx)+1500) {
                skipping = true;
            }

            //AKLOGI("[%d] %d: %d (%s) %c", whisper_full_n_segments_from_state(state), i, tokens[i].id, whisper_token_to_str(ctx, tokens[i].id), skipping ? '>' : ' ');

            if(skipping) continue;
            partial += whisper_token_to_str(ctx, tokens[i].id);
        }

        auto *wstate = reinterpret_cast<WhisperModelState *>(user_data);
        wstate->partial_results[whisper_full_n_segments_from_state(state)] = partial;

        // Add previous segment partials
        std::string final_partial;
        for(int i=0; i<whisper_full_n_segments_from_state(state); i++) {
            if(wstate->partial_results.count(i))
                final_partial.append(wstate->partial_results[i]);
        }

        final_partial.append(partial);

        AKLOGI("Sending partial result: %s", final_partial.c_str());
        
        if (wstate->partial_result_method != nullptr) {
            jstring pjstr = string2jstring(wstate->env, final_partial.c_str());
            wstate->env->CallVoidMethod(wstate->partial_result_instance, wstate->partial_result_method, pjstr);
            
            // Check for JNI exceptions
            if (wstate->env->ExceptionCheck()) {
                AKLOGE("JNI exception occurred in partial callback");
                wstate->env->ExceptionDescribe();
                wstate->env->ExceptionClear();
            }
            
            wstate->env->DeleteLocalRef(pjstr);
        } else {
            AKLOGE("partial_result_method is null, cannot send partial result");
        }
    };

    wparams.abort_callback_user_data = state;
    wparams.abort_callback = [](void * user_data) -> bool {
        auto *wstate = reinterpret_cast<WhisperModelState *>(user_data);

        if(std::find(wstate->last_forbidden_languages.begin(),
                     wstate->last_forbidden_languages.end(),
                     whisper_full_lang_id(wstate->context)) != wstate->last_forbidden_languages.end()) {
            return true;
        }

        if(wstate->cancel_flag) {
            AKLOGI("cancel flag set! Aborting...");
            return true;
        }

        return false;
    };

    AKLOGI("[VOICE] Calling whisper_full...");
    int res = whisper_full(state->context, wparams, samples, (int)num_samples);
    if(res != 0) {
        AKLOGE("[VOICE] WhisperGGML whisper_full failed with non-zero code %d", res);
    }
    AKLOGI("[VOICE] whisper_full finished with result code: %d", res);

    // Log detected language
    int detected_lang_id = whisper_full_lang_id(state->context);
    const char* detected_lang_str = whisper_lang_str(detected_lang_id);
    AKLOGI("[VOICE] === DETECTED LANGUAGE ===");
    AKLOGI("[VOICE]   ID: %d", detected_lang_id);
    AKLOGI("[VOICE]   Code: %s", detected_lang_str ? detected_lang_str : "unknown");

    // Get and log language detection probabilities
    AKLOGI("[VOICE] === LANGUAGE DETECTION CONFIDENCE ===");

    // Allocate array for language probabilities
    std::vector<float> lang_probs(whisper_lang_max_id() + 1, 0.0f);

    // Run language detection to get probabilities
    // Note: We use the same audio samples we just processed
    int auto_detected_lang = whisper_lang_auto_detect(
        state->context,
        0,  // offset_ms
        2,  // n_threads (use 2 threads for detection)
        lang_probs.data()
    );

    AKLOGI("[VOICE] Auto-detected language: %s (id=%d)",
           whisper_lang_str(auto_detected_lang), auto_detected_lang);

    // Sort languages by probability
    std::vector<std::pair<int, float>> lang_prob_pairs;
    for (int i = 0; i <= whisper_lang_max_id(); i++) {
        if (lang_probs[i] > 0.01f) {  // Only show languages with >1% probability
            lang_prob_pairs.push_back({i, lang_probs[i]});
        }
    }
    std::sort(lang_prob_pairs.begin(), lang_prob_pairs.end(),
              [](const auto& a, const auto& b) { return a.second > b.second; });

    // Log top 5 languages with highest probability
    AKLOGI("[VOICE] Top language probabilities:");
    for (size_t i = 0; i < std::min(size_t(5), lang_prob_pairs.size()); i++) {
        const char* lang_str = whisper_lang_str(lang_prob_pairs[i].first);
        AKLOGI("[VOICE]   %d. %s: %.2f%%",
               i+1, lang_str ? lang_str : "unknown",
               lang_prob_pairs[i].second * 100.0f);
    }

    // Log whether the detected language matches any allowed language
    if (!allowed_languages.empty()) {
        bool matches_allowed = std::find(allowed_languages.begin(), allowed_languages.end(),
                                        detected_lang_id) != allowed_languages.end();
        AKLOGI("[VOICE] Detected language %s allowed languages",
               matches_allowed ? "MATCHES" : "DOES NOT MATCH");
    }

    whisper_print_timings(state->context);

    std::string output = "";
    const int n_segments = whisper_full_n_segments(state->context);
    AKLOGI("[VOICE] Number of segments: %d", n_segments);

    for (int i = 0; i < n_segments; i++) {
        auto seg = std::string(whisper_full_get_segment_text(state->context, i));
        if(seg == " you" && i == n_segments - 1) continue;
        output.append(seg);

        AKLOGI("[VOICE] Segment[%d]: '%s'", i, seg.c_str());
    }

    if(std::find(forbidden_languages.begin(),
                 forbidden_languages.end(),
                 detected_lang_id) != forbidden_languages.end()) {
        AKLOGI("[VOICE] Detected language %s is in forbidden list - cancelling", detected_lang_str);
        output = "<>CANCELLED<> lang=" + std::string(detected_lang_str);
    }

    if(state->cancel_flag) {
        AKLOGI("[VOICE] Cancel flag set - cancelling");
        output = "<>CANCELLED<> flag";
    }

    AKLOGI("[VOICE] Final output: '%s'", output.c_str());

    jstring jstr = string2jstring(env, output.c_str());
    return jstr;
}

JNIEXPORT void JNICALL Java_helium314_keyboard_voice_whisper_WhisperGGML_cancelNative
  (JNIEnv *env, jobject obj, jlong handle) {
    auto *state = reinterpret_cast<WhisperModelState *>(handle);
    state->cancel_flag = 1;
}

JNIEXPORT void JNICALL Java_helium314_keyboard_voice_whisper_WhisperGGML_closeNative
  (JNIEnv *env, jobject obj, jlong handle) {
    auto *state = reinterpret_cast<WhisperModelState *>(handle);
    if(!state) return;

    whisper_free(state->context);

    delete state;
}