package helium314.keyboard.voice.whisper;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Loads Whisper models from assets based on language
 */
public class WhisperModelLoader {
    private static final String TAG = "WhisperModelLoader";
    
    private static final String ENGLISH_MODEL_PATH = "voice/voice-input-english-39.bin.tflite";
    private static final String MULTILINGUAL_MODEL_PATH = "voice/voice-input-multilingual-74.bin.tflite";
    
    private final Context context;
    
    public WhisperModelLoader(Context context) {
        this.context = context;
    }
    
    /**
     * Check if any model is available
     * @return true if at least one model exists
     */
    public boolean hasAnyModel() {
        try {
            String[] models = context.getAssets().list("voice");
            return models != null && models.length > 0;
        } catch (IOException e) {
            Log.e(TAG, "Error checking for models", e);
            return false;
        }
    }
    
    /**
     * Get the appropriate model for the given language
     * @param languageCode ISO language code (e.g., "en", "es", "fr")
     * @return Path to the model file in assets
     */
    public String getModelPathForLanguage(String languageCode) {
        if ("en".equals(languageCode)) {
            Log.d(TAG, "Using English model for language: " + languageCode);
            return ENGLISH_MODEL_PATH;
        } else {
            Log.d(TAG, "Using multilingual model for language: " + languageCode);
            return MULTILINGUAL_MODEL_PATH;
        }
    }
    
    /**
     * Load model as ByteBuffer from assets
     * @param languageCode ISO language code
     * @return Direct ByteBuffer containing the model
     * @throws IOException if model cannot be loaded
     */
    public ByteBuffer loadModelForLanguage(String languageCode) throws IOException {
        String modelPath = getModelPathForLanguage(languageCode);
        return loadModelFromAssets(modelPath);
    }
    
    /**
     * Load model from assets as a memory-mapped ByteBuffer
     * @param assetPath Path to the model in assets
     * @return Direct ByteBuffer containing the model
     * @throws IOException if model cannot be loaded
     */
    private ByteBuffer loadModelFromAssets(String assetPath) throws IOException {
        try (AssetFileDescriptor fileDescriptor = context.getAssets().openFd(assetPath)) {
            try (FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor())) {
                FileChannel fileChannel = inputStream.getChannel();
                long startOffset = fileDescriptor.getStartOffset();
                long declaredLength = fileDescriptor.getDeclaredLength();
                
                // Memory-map the model file for efficient access
                ByteBuffer buffer = fileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    startOffset,
                    declaredLength
                );
                
                Log.d(TAG, "Loaded model from " + assetPath + " (" + declaredLength + " bytes)");
                return buffer;
            }
        }
    }
    
    /**
     * Convert language code to Whisper language format
     * @param languageCode ISO language code (e.g., "en", "es")
     * @return Whisper language string
     */
    public static String toWhisperLanguage(String languageCode) {
        // Whisper uses lowercase ISO 639-1 codes
        if (languageCode == null || languageCode.isEmpty()) {
            return "";
        }
        
        // Handle special cases
        switch (languageCode.toLowerCase()) {
            case "zh":
            case "zh_cn":
            case "zh_tw":
                return "zh";
            case "pt":
            case "pt_br":
            case "pt_pt":
                return "pt";
            case "no":
            case "nb":
            case "nn":
                return "no"; // Norwegian
            default:
                // Return the base language code
                if (languageCode.contains("_")) {
                    return languageCode.substring(0, languageCode.indexOf('_')).toLowerCase();
                }
                return languageCode.toLowerCase();
        }
    }
    
    /**
     * Get all supported languages for the multilingual model
     * @return Array of Whisper language codes
     */
    public static String[] getMultilingualLanguages() {
        // List of languages supported by the multilingual model
        // This is a subset - the full model supports 99 languages
        return new String[] {
            "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr",
            "pl", "ca", "nl", "ar", "sv", "it", "id", "hi", "fi", "vi",
            "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no",
            "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk",
            "te", "fa", "lv", "bn", "sr", "az", "sl", "kn", "et", "mk",
            "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw",
            "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc",
            "ka", "be", "tg", "sd", "gu", "am", "yi", "lo", "uz", "fo",
            "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl",
            "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su", "yue"
        };
    }
}