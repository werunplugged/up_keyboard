// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.recognition;

/**
 * Interface for voice recognition engines.
 * This allows us to swap out different recognition implementations
 * without changing the UI or audio recording logic.
 */
public interface VoiceRecognitionEngine {
    
    /**
     * Listener for recognition events
     */
    interface RecognitionListener {
        void onRecognitionResult(String text, String languageCode);
        void onRecognitionError(String error);
        void onRecognitionStarted();
        void onRecognitionFinished();
        void onPartialResult(String partialText);
    }
    
    /**
     * Initialize the recognition engine
     * @return true if initialization successful
     */
    boolean initialize();
    
    /**
     * Start recognition with audio data
     * @param audioData Float array of audio samples (16kHz mono expected)
     * @param languageHint Optional language hint (e.g., "en", "es", "fr")
     */
    void recognize(float[] audioData, String languageHint);
    
    /**
     * Check if the engine is available and ready
     * @return true if engine can be used
     */
    boolean isAvailable();
    
    /**
     * Check if recognition is currently in progress
     * @return true if actively recognizing
     */
    boolean isRecognizing();
    
    /**
     * Cancel any ongoing recognition
     */
    void cancelRecognition();
    
    /**
     * Set the recognition listener
     * @param listener Listener for recognition events
     */
    void setListener(RecognitionListener listener);
    
    /**
     * Clean up resources
     */
    void cleanup();
    
    /**
     * Get the name of this recognition engine
     * @return Engine name for display/logging
     */
    String getEngineName();
    
    /**
     * Get supported languages by this engine
     * @return Array of language codes (e.g., ["en", "es", "fr"])
     *         Empty array means all languages supported or auto-detection
     */
    String[] getSupportedLanguages();
}