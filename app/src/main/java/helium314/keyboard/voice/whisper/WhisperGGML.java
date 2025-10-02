package helium314.keyboard.voice.whisper;

import android.util.Log;
import androidx.annotation.Keep;

import java.nio.Buffer;
import java.nio.ByteBuffer;

/**
 * JNI wrapper for Whisper C++ implementation
 * Adapted from Futo keyboard's WhisperGGML
 */
public class WhisperGGML {
    private static final String TAG = "WhisperGGML";
    private long handle = 0L;
    private PartialResultCallback partialResultCallback;
    
    public enum DecodingMode {
        GREEDY(0),
        BEAM_SEARCH_5(5);
        
        private final int value;
        
        DecodingMode(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
    }
    
    public static class BailLanguageException extends Exception {
        private final String language;
        
        public BailLanguageException(String language) {
            this.language = language;
        }
        
        public String getLanguage() {
            return language;
        }
    }
    
    public static class InferenceCancelledException extends Exception {}
    
    public static class InvalidModelException extends Exception {
        public InvalidModelException(String message) {
            super(message);
        }
    }
    
    public interface PartialResultCallback {
        void onPartialResult(String text);
    }
    
    private static boolean nativeLibraryAvailable = false;
    
    static {
        try {
            System.loadLibrary("whisperggml");
            nativeLibraryAvailable = true;
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "WhisperGGML native library not available, will use fallback");
        }
    }
    
    public static boolean isNativeLibraryAvailable() {
        return nativeLibraryAvailable;
    }
    
    /**
     * Create WhisperGGML instance from model buffer
     * @param modelBuffer Direct ByteBuffer containing the model
     * @throws InvalidModelException if model cannot be loaded
     */
    public WhisperGGML(ByteBuffer modelBuffer) throws InvalidModelException {
        handle = openFromBufferNative(modelBuffer);
        if (handle == 0L) {
            throw new InvalidModelException("The Whisper model could not be loaded from the given buffer");
        }
    }
    
    /**
     * Create WhisperGGML instance from model file path
     * @param modelPath Path to the model file
     * @throws InvalidModelException if model cannot be loaded
     */
    public WhisperGGML(String modelPath) throws InvalidModelException {
        handle = openNative(modelPath);
        if (handle == 0L) {
            throw new InvalidModelException("The Whisper model could not be loaded from: " + modelPath);
        }
    }
    
    @Keep
    private void invokePartialResult(String text) {
        Log.d(TAG, "invokePartialResult called with: " + text);
        if (partialResultCallback != null) {
            partialResultCallback.onPartialResult(text.trim());
        } else {
            Log.w(TAG, "partialResultCallback is null");
        }
    }
    
    /**
     * Set the partial result callback
     */
    public void setPartialResultCallback(PartialResultCallback callback) {
        this.partialResultCallback = callback;
    }
    
    /**
     * Run inference on audio samples (simplified version)
     */
    public String infer(
        float[] samples,
        String prompt,
        String[] languages,
        String[] bailLanguages,
        DecodingMode decodingMode,
        boolean suppressNonSpeechTokens
    ) throws BailLanguageException, InferenceCancelledException {
        return infer(samples, prompt, languages, bailLanguages, decodingMode, suppressNonSpeechTokens, partialResultCallback);
    }
    
    /**
     * Run inference on audio samples
     * @param samples Float array of audio samples (16kHz mono)
     * @param prompt Initial prompt/glossary terms
     * @param languages Array of allowed language codes (empty = autodetect)
     * @param bailLanguages Languages that should trigger model switch
     * @param decodingMode Greedy or beam search
     * @param suppressNonSpeechTokens Whether to suppress symbols
     * @param partialCallback Callback for partial results
     * @return Final transcription result
     * @throws BailLanguageException if a bail language is detected
     * @throws InferenceCancelledException if inference was cancelled
     */
    public String infer(
        float[] samples,
        String prompt,
        String[] languages,
        String[] bailLanguages,
        DecodingMode decodingMode,
        boolean suppressNonSpeechTokens,
        PartialResultCallback partialCallback
    ) throws BailLanguageException, InferenceCancelledException {
        if (handle == 0L) {
            throw new IllegalStateException("WhisperGGML has already been closed, cannot infer");
        }

        Log.d(TAG, "[VOICE] === WhisperGGML.infer() ===");
        Log.d(TAG, "[VOICE] Audio samples: " + samples.length);
        Log.d(TAG, "[VOICE] Prompt: \"" + prompt + "\"");
        Log.d(TAG, "[VOICE] Languages count: " + languages.length);
        for (int i = 0; i < languages.length; i++) {
            Log.d(TAG, "[VOICE] Language[" + i + "]: " + languages[i]);
        }
        Log.d(TAG, "[VOICE] Bail languages count: " + bailLanguages.length);
        for (int i = 0; i < bailLanguages.length; i++) {
            Log.d(TAG, "[VOICE] Bail language[" + i + "]: " + bailLanguages[i]);
        }
        Log.d(TAG, "[VOICE] Decoding mode: " + decodingMode.name() + " (value=" + decodingMode.getValue() + ")");
        Log.d(TAG, "[VOICE] Suppress non-speech tokens: " + suppressNonSpeechTokens);

        this.partialResultCallback = partialCallback;

        Log.d(TAG, "[VOICE] Calling native inferNative()...");
        String result = inferNative(
            handle,
            samples,
            prompt,
            languages,
            bailLanguages,
            decodingMode.getValue(),
            suppressNonSpeechTokens
        ).trim();

        Log.d(TAG, "[VOICE] Native inference returned: \"" + result + "\"");

        // Check for special cancellation markers
        if (result.contains("<>CANCELLED<>")) {
            if (result.contains("flag")) {
                Log.d(TAG, "[VOICE] Inference was cancelled by flag");
                throw new InferenceCancelledException();
            } else if (result.contains("lang=")) {
                String language = result.split("lang=")[1];
                Log.d(TAG, "[VOICE] Bail language detected: " + language);
                throw new BailLanguageException(language);
            } else {
                Log.e(TAG, "[VOICE] Cancelled for unknown reason");
                throw new IllegalStateException("Cancelled for unknown reason");
            }
        }

        Log.d(TAG, "[VOICE] Returning final result: \"" + result + "\"");
        return result;
    }
    
    /**
     * Cancel ongoing inference
     */
    public void cancel() {
        if (handle != 0L) {
            cancelNative(handle);
        }
    }
    
    /**
     * Close and release resources
     */
    public void close() {
        if (handle != 0L) {
            closeNative(handle);
            handle = 0L;
        }
    }
    
    @Override
    protected void finalize() throws Throwable {
        close();
        super.finalize();
    }
    
    // Native methods
    private native long openNative(String path);
    private native long openFromBufferNative(Buffer buffer);
    private native String inferNative(
        long handle,
        float[] samples,
        String prompt,
        String[] languages,
        String[] bailLanguages,
        int decodingMode,
        boolean suppressNonSpeechTokens
    );
    private native void cancelNative(long handle);
    private native void closeNative(long handle);
}