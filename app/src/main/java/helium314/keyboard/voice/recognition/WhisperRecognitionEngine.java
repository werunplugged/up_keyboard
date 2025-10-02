package helium314.keyboard.voice.recognition;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import helium314.keyboard.voice.whisper.WhisperGGML;
import helium314.keyboard.voice.whisper.WhisperModelLoader;

public class WhisperRecognitionEngine implements VoiceRecognitionEngine {
    private static final String TAG = "WhisperRecognition";
    
    private final Context context;
    private final WhisperModelLoader modelLoader;
    private final Map<String, WhisperGGML> modelCache = new HashMap<>();
    private WhisperGGML whisperInstance;
    private RecognitionListener listener;
    private String currentLanguage = null;
    
    // Threading
    private ExecutorService executorService;
    private Future<?> currentTask;
    private boolean isProcessing = false;
    
    // Audio buffer for accumulating samples
    private final List<Float> audioBuffer = new ArrayList<>();
    private static final int SAMPLE_RATE = 16000;
    private static final int MAX_RECORDING_SECONDS = 30;
    private static final int MAX_SAMPLES = SAMPLE_RATE * MAX_RECORDING_SECONDS;
    
    public WhisperRecognitionEngine(Context context) {
        this.context = context;
        this.modelLoader = new WhisperModelLoader(context);
        this.executorService = Executors.newSingleThreadExecutor();
    }
    
    private void ensureExecutorService() {
        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newSingleThreadExecutor();
        }
    }
    
    @Override
    public boolean initialize() {
        Log.d(TAG, "Initializing WhisperRecognitionEngine");
        
        // Check if native library is available
        if (!WhisperGGML.isNativeLibraryAvailable()) {
            Log.w(TAG, "WhisperGGML native library not available");
            return false;
        }
        
        return true;
    }
    
    private void initializeForLanguage(String languageCode) {
        Log.d(TAG, "[VOICE] === initializeForLanguage() ===");
        Log.d(TAG, "[VOICE] Requested language: " + languageCode);

        // Get the model path for this language
        String modelPath = modelLoader.getModelPathForLanguage(languageCode);
        Log.d(TAG, "[VOICE] Model path: " + modelPath);

        // Check if we already have this model cached
        if (modelCache.containsKey(modelPath)) {
            whisperInstance = modelCache.get(modelPath);
            currentLanguage = languageCode;
            Log.d(TAG, "[VOICE] Using CACHED model for language: " + languageCode);
            return;
        }

        // Load new model if not cached
        try {
            Log.d(TAG, "[VOICE] Loading NEW model for language: " + languageCode);
            Log.d(TAG, "[VOICE] Model file: " + modelPath);
            ByteBuffer modelBuffer = modelLoader.loadModelForLanguage(languageCode);
            if (modelBuffer != null) {
                Log.d(TAG, "[VOICE] Model buffer loaded, creating WhisperGGML instance...");
                WhisperGGML newInstance = new WhisperGGML(modelBuffer);
                modelCache.put(modelPath, newInstance);
                whisperInstance = newInstance;
                currentLanguage = languageCode;
                Log.d(TAG, "[VOICE] Model loaded and cached successfully");
                Log.d(TAG, "[VOICE] Model type: " + (languageCode.equals("en") ? "English-only" : "Multilingual"));
            } else {
                Log.e(TAG, "[VOICE] Failed to load model buffer for language: " + languageCode);
                if (listener != null) {
                    listener.onRecognitionError("Failed to load voice model");
                }
            }
        } catch (WhisperGGML.InvalidModelException e) {
            Log.e(TAG, "[VOICE] Invalid model exception", e);
            if (listener != null) {
                listener.onRecognitionError("Invalid voice model");
            }
        } catch (IOException e) {
            Log.e(TAG, "[VOICE] IO exception loading model", e);
            if (listener != null) {
                listener.onRecognitionError("Error loading voice model");
            }
        }
    }
    
    private boolean needsModelSwitch(String oldLang, String newLang) {
        // Both English -> no switch needed
        if ("en".equals(oldLang) && "en".equals(newLang)) {
            return false;
        }
        // Both non-English -> no switch needed (use multilingual)
        if (!"en".equals(oldLang) && !"en".equals(newLang)) {
            return false;
        }
        // Switching between English and non-English -> switch needed
        return true;
    }

    /**
     * Normalize language codes to Whisper's expected format.
     * Maps deprecated/alternative ISO 639 codes to current standard codes.
     */
    private String normalizeLanguageCode(String languageCode) {
        switch (languageCode) {
            case "iw": return "he";  // Hebrew: iw (deprecated) -> he
            case "in": return "id";  // Indonesian: in (deprecated) -> id
            case "ji": return "yi";  // Yiddish: ji (deprecated) -> yi
            default: return languageCode;
        }
    }
    
    @Override
    public void recognize(float[] audioData, String languageHint) {
        Log.d(TAG, "[VOICE] ===== WhisperRecognitionEngine.recognize() =====");
        Log.d(TAG, "[VOICE] Audio data length: " + audioData.length + " samples");
        Log.d(TAG, "[VOICE] Raw language hint: " + languageHint);

        if (languageHint == null || languageHint.isEmpty()) {
            languageHint = "en"; // Default to English
            Log.d(TAG, "[VOICE] No language hint provided, defaulting to: en");
        }

        // Parse multiple languages if comma-separated
        String[] languageArray = languageHint.contains(",") ?
            languageHint.split(",") : new String[]{languageHint};
        String primaryLanguage = languageArray[0];

        Log.d(TAG, "[VOICE] Parsed languages count: " + languageArray.length);
        for (int i = 0; i < languageArray.length; i++) {
            Log.d(TAG, "[VOICE] Language[" + i + "]: " + languageArray[i] +
                  (i == 0 ? " (PRIMARY)" : ""));
        }

        // Initialize for the primary language if needed
        if (whisperInstance == null || !primaryLanguage.equals(currentLanguage)) {
            Log.d(TAG, "[VOICE] Initializing model for primary language: " + primaryLanguage);
            Log.d(TAG, "[VOICE] Previous language was: " + currentLanguage);
            initializeForLanguage(primaryLanguage);
        } else {
            Log.d(TAG, "[VOICE] Model already initialized for: " + currentLanguage);
        }

        if (whisperInstance == null) {
            Log.e(TAG, "[VOICE] Failed to initialize Whisper instance");
            if (listener != null) {
                listener.onRecognitionError("Voice recognition not initialized");
            }
            return;
        }

        if (isProcessing) {
            Log.w(TAG, "[VOICE] Recognition already in progress");
            return;
        }

        isProcessing = true;
        if (listener != null) {
            listener.onRecognitionStarted();
        }

        // Ensure executor service is available
        ensureExecutorService();

        final String[] finalLanguages = languageArray;
        final String finalPrimaryLanguage = primaryLanguage;
        currentTask = executorService.submit(() -> {
            try {
                // Set up partial result callback
                whisperInstance.setPartialResultCallback(text -> {
                    Log.d(TAG, "[VOICE] Partial result: \"" + text + "\"");
                    if (listener != null) {
                        listener.onPartialResult(text);
                    }
                });

                // Prepare language hints - normalize to Whisper's expected codes
                String[] normalizedLanguages = new String[finalLanguages.length];
                for (int i = 0; i < finalLanguages.length; i++) {
                    normalizedLanguages[i] = normalizeLanguageCode(finalLanguages[i]);
                    if (!normalizedLanguages[i].equals(finalLanguages[i])) {
                        Log.d(TAG, "[VOICE] Normalized language: " + finalLanguages[i] + " -> " + normalizedLanguages[i]);
                    }
                }

                String[] languages;
                if (normalizedLanguages.length == 1) {
                    // Single language - strict lock (pass twice for enforcement)
                    languages = new String[]{normalizedLanguages[0], normalizedLanguages[0]};
                    Log.d(TAG, "[VOICE] *** STRICT LOCK MODE ***");
                    Log.d(TAG, "[VOICE] Duplicating language for strict lock: " + normalizedLanguages[0]);
                    Log.d(TAG, "[VOICE] Passing to JNI: [" + languages[0] + ", " + languages[1] + "]");
                } else {
                    // Multiple languages - restricted auto-detection
                    languages = normalizedLanguages;
                    Log.d(TAG, "[VOICE] *** MULTI-LANGUAGE MODE ***");
                    Log.d(TAG, "[VOICE] Languages for restricted auto-detection: " + String.join(", ", normalizedLanguages));
                }

                // No bail languages for now
                String[] bailLanguages = new String[]{};
                Log.d(TAG, "[VOICE] Bail languages: " + (bailLanguages.length == 0 ? "none" : String.join(", ", bailLanguages)));

                Log.d(TAG, "[VOICE] Starting Whisper inference...");
                Log.d(TAG, "[VOICE] Model: " + (primaryLanguage.equals("en") ? "English" : "Multilingual"));

                // Run inference with language enforcement
                String result = whisperInstance.infer(
                    audioData,
                    "", // No prompt
                    languages,
                    bailLanguages,
                    WhisperGGML.DecodingMode.GREEDY,
                    true // Suppress non-speech tokens
                );

                Log.d(TAG, "[VOICE] Inference completed");

                // Handle result
                if (result != null && !result.isEmpty()) {
                    Log.d(TAG, "[VOICE] Recognition result: \"" + result + "\"");
                    Log.d(TAG, "[VOICE] Reporting language as: " + finalPrimaryLanguage);
                    if (listener != null) {
                        // Return the primary language for consistency
                        listener.onRecognitionResult(result, finalPrimaryLanguage);
                    }
                } else {
                    Log.d(TAG, "[VOICE] Recognition result: EMPTY");
                    if (listener != null) {
                        listener.onRecognitionResult("", finalPrimaryLanguage);
                    }
                }

            } catch (WhisperGGML.BailLanguageException e) {
                Log.d(TAG, "Bail language detected: " + e.getLanguage());
                if (listener != null) {
                    // Return empty result but keep the primary language
                    listener.onRecognitionResult("", finalPrimaryLanguage);
                }
            } catch (WhisperGGML.InferenceCancelledException e) {
                Log.d(TAG, "Inference cancelled");
            } catch (Exception e) {
                Log.e(TAG, "Recognition error", e);
                if (listener != null) {
                    listener.onRecognitionError("Voice recognition failed");
                }
            } finally {
                isProcessing = false;
                if (listener != null) {
                    listener.onRecognitionFinished();
                }
            }
        });
    }
    
    @Override
    public boolean isAvailable() {
        return WhisperGGML.isNativeLibraryAvailable() && modelLoader.hasAnyModel();
    }
    
    @Override
    public boolean isRecognizing() {
        return isProcessing;
    }
    
    @Override
    public void cancelRecognition() {
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(true);
        }
        if (whisperInstance != null) {
            whisperInstance.cancel();
        }
        isProcessing = false;
    }
    
    @Override
    public void setListener(RecognitionListener listener) {
        this.listener = listener;
    }
    
    @Override
    public void cleanup() {
        cancelRecognition();
        // Close all cached models
        for (WhisperGGML instance : modelCache.values()) {
            instance.close();
        }
        modelCache.clear();
        whisperInstance = null;
        currentLanguage = null;
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
    
    @Override
    public String getEngineName() {
        return "Whisper (Built-in)";
    }
    
    @Override
    public String[] getSupportedLanguages() {
        // Whisper supports many languages, return empty array to indicate auto-detection
        return new String[]{};
    }
}