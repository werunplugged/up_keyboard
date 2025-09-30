// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice;

import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import helium314.keyboard.voice.recognition.PlaceholderRecognitionEngine;
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
        // Try to use Whisper engine if available, fallback to placeholder
        WhisperRecognitionEngine whisperEngine = new WhisperRecognitionEngine(context);
        if (whisperEngine.isAvailable()) {
            Log.d(TAG, "Using Whisper recognition engine");
            recognitionEngine = whisperEngine;
        } else {
            Log.d(TAG, "Whisper not available, using placeholder engine");
            recognitionEngine = new PlaceholderRecognitionEngine(context);
        }
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
            Log.w(TAG, "Voice input already active");
            return;
        }
        
        Log.d(TAG, "Showing voice input with language hint: " + languageHint);
        isVoiceInputActive = true;
        
        // Initialize recognition engine with language
        if (recognitionEngine != null) {
            recognitionEngine.initialize();
        }
        
        // Set language hint
        if (voiceInputView != null) {
            voiceInputView.setLanguageHint(languageHint);
        }
        
        if (listener != null) {
            listener.onVoiceInputStarted();
        }
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