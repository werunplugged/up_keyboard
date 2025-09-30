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
        Log.d(TAG, "Initializing for language: " + languageCode);
        
        // Get the model path for this language
        String modelPath = modelLoader.getModelPathForLanguage(languageCode);
        
        // Check if we already have this model cached
        if (modelCache.containsKey(modelPath)) {
            whisperInstance = modelCache.get(modelPath);
            currentLanguage = languageCode;
            Log.d(TAG, "Using cached model for language: " + languageCode);
            return;
        }
        
        // Load new model if not cached
        try {
            ByteBuffer modelBuffer = modelLoader.loadModelForLanguage(languageCode);
            if (modelBuffer != null) {
                WhisperGGML newInstance = new WhisperGGML(modelBuffer);
                modelCache.put(modelPath, newInstance);
                whisperInstance = newInstance;
                currentLanguage = languageCode;
                Log.d(TAG, "Loaded and cached model for language: " + languageCode);
            } else {
                Log.e(TAG, "Failed to load model for language: " + languageCode);
                if (listener != null) {
                    listener.onRecognitionError("Failed to load voice model");
                }
            }
        } catch (WhisperGGML.InvalidModelException e) {
            Log.e(TAG, "Invalid model", e);
            if (listener != null) {
                listener.onRecognitionError("Invalid voice model");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error loading model", e);
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
    
    @Override
    public void recognize(float[] audioData, String languageHint) {
        if (languageHint == null || languageHint.isEmpty()) {
            languageHint = "en"; // Default to English
        }
        
        // Initialize for the requested language if needed
        if (whisperInstance == null || !languageHint.equals(currentLanguage)) {
            initializeForLanguage(languageHint);
        }
        
        if (whisperInstance == null) {
            if (listener != null) {
                listener.onRecognitionError("Voice recognition not initialized");
            }
            return;
        }
        
        if (isProcessing) {
            Log.w(TAG, "Recognition already in progress");
            return;
        }
        
        isProcessing = true;
        if (listener != null) {
            listener.onRecognitionStarted();
        }
        
        // Ensure executor service is available
        ensureExecutorService();
        
        final String finalLanguageHint = languageHint;
        currentTask = executorService.submit(() -> {
            try {
                // Set up partial result callback
                whisperInstance.setPartialResultCallback(text -> {
                    Log.d(TAG, "Partial result callback received: " + text);
                    if (listener != null) {
                        listener.onPartialResult(text);
                    }
                });
                
                // Prepare language hints
                String[] languages = new String[]{finalLanguageHint};
                String[] bailLanguages = new String[]{}; // No bail languages for now
                
                // Run inference
                String result = whisperInstance.infer(
                    audioData,
                    "", // No prompt
                    languages,
                    bailLanguages,
                    WhisperGGML.DecodingMode.GREEDY,
                    true // Suppress non-speech tokens
                );
                
                // Handle result
                if (result != null && !result.isEmpty()) {
                    if (listener != null) {
                        listener.onRecognitionResult(result, currentLanguage);
                    }
                } else {
                    if (listener != null) {
                        listener.onRecognitionResult("", currentLanguage);
                    }
                }
                
            } catch (WhisperGGML.BailLanguageException e) {
                Log.d(TAG, "Bail language detected: " + e.getLanguage());
                if (listener != null) {
                    listener.onRecognitionResult("", e.getLanguage());
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