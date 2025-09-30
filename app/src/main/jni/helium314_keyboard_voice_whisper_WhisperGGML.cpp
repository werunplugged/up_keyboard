#include <string>
#include <vector>
#include <map>
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
    
    AKLOGI("Attempting to infer model...");

    auto *state = reinterpret_cast<WhisperModelState *>(handle);
    state->cancel_flag = 0;

    std::vector<int> allowed_languages;
    int num_languages = env->GetArrayLength(languages);
    for (int i=0; i<num_languages; i++) {
        jstring jstr = static_cast<jstring>(env->GetObjectArrayElement(languages, i));
        std::string str = jstring2string(env, jstr);

        allowed_languages.push_back(whisper_lang_id(str.c_str()));
    }


    std::vector<int> forbidden_languages;
    int num_bail_languages = env->GetArrayLength(bail_languages);
    for (int i=0; i<num_bail_languages; i++) {
        jstring jstr = static_cast<jstring>(env->GetObjectArrayElement(bail_languages, i));
        std::string str = jstring2string(env, jstr);

        forbidden_languages.push_back(whisper_lang_id(str.c_str()));
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

    if(allowed_languages.size() == 0) {
        wparams.language = nullptr;
    }else if(allowed_languages.size() == 1) {
        wparams.language = whisper_lang_str(allowed_languages[0]);
    }else{
        wparams.language = nullptr;
        wparams.allowed_langs = allowed_languages.data();
        wparams.allowed_langs_size = allowed_languages.size();
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

    AKLOGI("Calling whisper_full");
    int res = whisper_full(state->context, wparams, samples, (int)num_samples);
    if(res != 0) {
        AKLOGE("WhisperGGML whisper_full failed with non-zero code %d", res);
    }
    AKLOGI("whisper_full finished");

    whisper_print_timings(state->context);

    std::string output = "";
    const int n_segments = whisper_full_n_segments(state->context);

    for (int i = 0; i < n_segments; i++) {
        auto seg = std::string(whisper_full_get_segment_text(state->context, i));
        if(seg == " you" && i == n_segments - 1) continue;
        output.append(seg);

        //AKLOGI("Final segment [%d]: %s", i, seg.c_str());
    }

    if(std::find(forbidden_languages.begin(),
                 forbidden_languages.end(),
                 whisper_full_lang_id(state->context)) != forbidden_languages.end()) {
        output = "<>CANCELLED<> lang=" + std::string(whisper_lang_str(whisper_full_lang_id(state->context)));
    }

    if(state->cancel_flag) {
        output = "<>CANCELLED<> flag";
    }

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