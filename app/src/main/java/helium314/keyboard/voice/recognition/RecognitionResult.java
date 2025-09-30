// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.recognition;

/**
 * Represents the result of a voice recognition operation
 */
public class RecognitionResult {
    private final String text;
    private final String languageCode;
    private final float confidence;
    private final long timestamp;
    
    public RecognitionResult(String text, String languageCode, float confidence) {
        this.text = text;
        this.languageCode = languageCode;
        this.confidence = confidence;
        this.timestamp = System.currentTimeMillis();
    }
    
    public String getText() {
        return text;
    }
    
    public String getLanguageCode() {
        return languageCode;
    }
    
    public float getConfidence() {
        return confidence;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    @Override
    public String toString() {
        return "RecognitionResult{" +
                "text='" + text + '\'' +
                ", languageCode='" + languageCode + '\'' +
                ", confidence=" + confidence +
                '}';
    }
}