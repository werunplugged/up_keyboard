// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice;

import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import helium314.keyboard.voice.recognition.VoiceRecognitionEngine;
import helium314.keyboard.voice.recognition.WhisperRecognitionEngine;
import helium314.keyboard.voice.ui.VoiceInputView;

/**
 * Manager class for voice input functionality.
 * Coordinates between the keyboard, voice UI, and recognition engine.
 */
public class VoiceInputManager {
    private static final String TAG = "VoiceInputManager";
    
    private final Context context;
    private InputMethodService inputMethodService;
    private VoiceInputView voiceInputView;
    private VoiceRecognitionEngine recognitionEngine;
    private ViewGroup keyboardView;
    private boolean isVoiceInputActive = false;
    
    // Listener for voice input events
    public interface VoiceInputListener {
        void onVoiceInputStarted();
        void onVoiceInputResult(String text, String languageCode);
        void onVoiceInputCanceled();
        void onVoiceInputError(String error);
    }
    
    private VoiceInputListener listener;
    
    public VoiceInputManager(Context context) {
        this.context = context;
        initializeRecognitionEngine();
    }
    
    private void initializeRecognitionEngine() {
        // Use Whisper recognition engine
        recognitionEngine = new WhisperRecognitionEngine(context);
        Log.d(TAG, "Using Whisper recognition engine");
    }
    
    public void setInputMethodService(InputMethodService ims) {
        this.inputMethodService = ims;
    }
    
    public void setKeyboardView(ViewGroup keyboardView) {
        this.keyboardView = keyboardView;
    }
    
    public void setListener(VoiceInputListener listener) {
        this.listener = listener;
    }
    
    /**
     * Initialize voice input view with the keyboard's voice view
     * @param voiceView The voice input view from the keyboard layout
     */
    public void initializeVoiceView(VoiceInputView voiceView) {
        this.voiceInputView = voiceView;
        if (voiceView != null) {
            voiceView.setRecognitionEngine(recognitionEngine);
            voiceView.setOnVoiceResultListener(new VoiceInputView.OnVoiceResultListener() {
                @Override
                public void onResult(String text, String languageCode) {
                    Log.d(TAG, "Voice result: " + text);
                    if (listener != null) {
                        listener.onVoiceInputResult(text, languageCode);
                    }
                }
                
                @Override
                public void onError(String error) {
                    Log.e(TAG, "Voice error: " + error);
                    if (listener != null) {
                        listener.onVoiceInputError(error);
                    }
                }
                
                @Override
                public void onRecordingStarted() {
                    Log.d(TAG, "Recording started");
                }
                
                @Override
                public void onRecordingStopped() {
                    Log.d(TAG, "Recording stopped");
                }
            });
        }
    }
    
    /**
     * Show voice input UI
     * @param languageHint Current keyboard language for recognition hint
     */
    public void showVoiceInput(String languageHint) {
        if (isVoiceInputActive) {
            Log.w(TAG, "[VOICE] Voice input already active");
            return;
        }

        Log.d(TAG, "[VOICE] ========================================");
        Log.d(TAG, "[VOICE] Showing voice input with language hint: " + languageHint);

        // Parse and log language details
        if (languageHint != null) {
            String[] languages = languageHint.split(",");
            Log.d(TAG, "[VOICE] Number of languages in hint: " + languages.length);
            for (int i = 0; i < languages.length; i++) {
                Log.d(TAG, "[VOICE] Language[" + i + "]: " + languages[i]);
            }

            if (languages.length == 1) {
                Log.d(TAG, "[VOICE] MODE: Single language - will use STRICT LOCK");
            } else {
                Log.d(TAG, "[VOICE] MODE: Multiple languages - will use RESTRICTED AUTO-DETECTION");
            }
        }

        isVoiceInputActive = true;

        // Initialize recognition engine with language
        if (recognitionEngine != null) {
            Log.d(TAG, "[VOICE] Initializing recognition engine: " + recognitionEngine.getEngineName());
            recognitionEngine.initialize();
        }

        // Set language hint
        if (voiceInputView != null) {
            Log.d(TAG, "[VOICE] Setting language hint on voice input view");
            voiceInputView.setLanguageHint(languageHint);
        }

        if (listener != null) {
            listener.onVoiceInputStarted();
        }
        Log.d(TAG, "[VOICE] ========================================");
    }
    
    /**
     * Hide voice input UI and restore keyboard
     */
    public void hideVoiceInput() {
        if (!isVoiceInputActive) {
            return;
        }
        
        Log.d(TAG, "Hiding voice input");
        isVoiceInputActive = false;
    }
    
    /**
     * Cancel voice input
     */
    public void cancelVoiceInput() {
        Log.d(TAG, "Canceling voice input");
        
        if (voiceInputView != null) {
            voiceInputView.stopRecording();
        }
        
        hideVoiceInput();
        
        if (listener != null) {
            listener.onVoiceInputCanceled();
        }
    }
    
    /**
     * Check if voice input is currently active
     */
    public boolean isVoiceInputActive() {
        return isVoiceInputActive;
    }
    
    /**
     * Set custom recognition engine
     * @param engine Recognition engine implementation
     */
    public void setRecognitionEngine(VoiceRecognitionEngine engine) {
        this.recognitionEngine = engine;
        if (engine != null) {
            engine.initialize();
        }
        if (voiceInputView != null) {
            voiceInputView.setRecognitionEngine(engine);
        }
    }
    
    /**
     * Clean up resources
     */
    public void cleanup() {
        if (voiceInputView != null) {
            voiceInputView.cleanup();
            voiceInputView = null;
        }
        if (recognitionEngine != null) {
            recognitionEngine.cleanup();
            recognitionEngine = null;
        }
    }
}