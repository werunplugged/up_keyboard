// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.widget.Toast;

import helium314.keyboard.latin.R;
import helium314.keyboard.voice.ui.VoiceInputView;

import java.util.ArrayList;

/**
 * Activity for system-wide voice recognition with UI.
 * Responds to ACTION_RECOGNIZE_SPEECH intent.
 * Shows VoiceInputView overlay for visual feedback during recording.
 */
public class VoiceRecognizeActivity extends Activity {
    private static final String TAG = "VoiceRecognizeActivity";

    private VoiceInputView voiceInputView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_recognize);

        Log.d(TAG, "Voice Recognize Activity started");

        // Get language hint from intent
        Intent intent = getIntent();
        String languageHint = intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        Log.d(TAG, "Language hint from intent: " + languageHint);

        // Initialize voice input view
        voiceInputView = findViewById(R.id.voice_input_view);

        // Set language hint if provided
        if (languageHint != null && voiceInputView != null) {
            voiceInputView.setLanguageHint(languageHint);
        }

        // Set up listener for voice results
        if (voiceInputView != null) {
            voiceInputView.setOnVoiceResultListener(new VoiceInputView.OnVoiceResultListener() {
                @Override
                public void onResult(String text, String languageCode) {
                    Log.d(TAG, "Voice result: " + text);
                    Log.d(TAG, "Language: " + languageCode);

                    // Return result to calling app
                    sendResult(text);
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "Voice error: " + error);
                    Toast.makeText(VoiceRecognizeActivity.this, error, Toast.LENGTH_SHORT).show();

                    // Return cancelled result
                    setResult(RESULT_CANCELED);
                    finish();
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

            // Auto-start recording when activity opens
            // Add slight delay to ensure UI is ready
            voiceInputView.postDelayed(() -> {
                if (voiceInputView != null) {
                    voiceInputView.startRecording();
                }
            }, 300);
        }
    }

    /**
     * Send recognition result back to the calling app
     */
    private void sendResult(String result) {
        Intent resultIntent = new Intent();
        ArrayList<String> results = new ArrayList<>();
        results.add(result);
        resultIntent.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results);
        resultIntent.putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, new float[]{1.0f});
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    @Override
    public void onBackPressed() {
        // User cancelled recognition
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (voiceInputView != null) {
            voiceInputView.cleanup();
        }
    }
}
