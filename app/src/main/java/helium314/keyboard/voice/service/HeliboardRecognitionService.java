// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.service;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.speech.RecognizerIntent;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.util.Log;

import helium314.keyboard.voice.audio.RecordBuffer;
import helium314.keyboard.voice.audio.Recorder;
import helium314.keyboard.voice.recognition.WhisperRecognitionEngine;
import helium314.keyboard.voice.recognition.VoiceRecognitionEngine;

import java.util.ArrayList;

/**
 * Android RecognitionService implementation for HeliBoard.
 * Provides system-level speech recognition using our voice recognition engine.
 */
public class HeliboardRecognitionService extends RecognitionService {
    private static final String TAG = "HeliboardRecognition";
    
    private Recorder mRecorder = null;
    private VoiceRecognitionEngine mRecognitionEngine = null;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Callback mCurrentCallback = null;
    private boolean mRecognitionCancelled = false;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Recognition service created");
        initializeRecognitionEngine();
    }
    
    private void initializeRecognitionEngine() {
        // Use Whisper recognition engine for system-wide voice input
        mRecognitionEngine = new WhisperRecognitionEngine(this);
        mRecognitionEngine.initialize();
    }
    
    @Override
    protected void onStartListening(Intent recognizerIntent, Callback callback) {
        Log.d(TAG, "onStartListening");
        mCurrentCallback = callback;
        mRecognitionCancelled = false;
        
        // Get language from intent
        String targetLang = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        Log.d(TAG, "Language from intent: " + targetLang);
        
        // Process language code
        String langCode = null;
        if (targetLang != null) {
            String[] parts = targetLang.split("[-_]");
            if (parts.length > 0) {
                langCode = parts[0].toLowerCase();
            }
        }
        Log.d(TAG, "Using language code: " + langCode);
        
        // Check microphone permission
        if (!checkRecordPermission()) {
            Log.e(TAG, "No microphone permission");
            try {
                callback.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
            } catch (RemoteException e) {
                Log.e(TAG, "Error sending permission error", e);
            }
            return;
        }
        
        // Initialize recorder if needed
        if (mRecorder == null) {
            mRecorder = new Recorder(this);
            setupRecorderListener(callback, langCode);
        }
        
        // Start recording with VAD
        if (!mRecorder.isInProgress()) {
            mRecorder.initVad(this);
            mRecorder.start();
            try {
                callback.beginningOfSpeech();
            } catch (RemoteException e) {
                Log.e(TAG, "Error sending beginningOfSpeech", e);
            }
        }
    }
    
    private void setupRecorderListener(final Callback callback, final String langCode) {
        mRecorder.setListener(new Recorder.RecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                if (message.equals(Recorder.MSG_RECORDING)) {
                    Log.d(TAG, "Recording started");
                    try {
                        callback.rmsChanged(10);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error sending rmsChanged", e);
                    }
                } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                    Log.d(TAG, "Recording done, starting transcription");
                    mHandler.post(() -> {
                        try {
                            callback.endOfSpeech();
                        } catch (RemoteException e) {
                            Log.e(TAG, "Error sending endOfSpeech", e);
                        }
                        startTranscription(callback, langCode);
                    });
                } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                    Log.e(TAG, "Recording error");
                    try {
                        callback.error(SpeechRecognizer.ERROR_CLIENT);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error sending error", e);
                    }
                } else if (message.equals(Recorder.MSG_PERMISSION_ERROR)) {
                    Log.e(TAG, "Permission error");
                    try {
                        callback.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error sending permission error", e);
                    }
                }
            }
            
            @Override
            public void onAudioBufferReceived(float[] audioBuffer) {
                // Optional: Could send RMS updates here for visualization
            }
        });
    }
    
    private void startTranscription(final Callback callback, final String langCode) {
        if (mRecognitionCancelled) {
            Log.d(TAG, "Recognition cancelled, not starting transcription");
            return;
        }
        
        if (mRecognitionEngine == null) {
            Log.e(TAG, "Recognition engine not initialized");
            try {
                callback.error(SpeechRecognizer.ERROR_CLIENT);
            } catch (RemoteException e) {
                Log.e(TAG, "Error sending error", e);
            }
            return;
        }
        
        // Set up recognition listener
        mRecognitionEngine.setListener(new VoiceRecognitionEngine.RecognitionListener() {
            @Override
            public void onRecognitionResult(String text, String languageCode) {
                Log.d(TAG, "Recognition result: " + text);
                if (!mRecognitionCancelled && text != null && !text.trim().isEmpty()) {
                    Bundle results = new Bundle();
                    ArrayList<String> resultList = new ArrayList<>();
                    resultList.add(text.trim());
                    results.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, resultList);
                    
                    try {
                        callback.results(results);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error sending results", e);
                    }
                }
            }
            
            @Override
            public void onRecognitionError(String error) {
                Log.e(TAG, "Recognition error: " + error);
                if (!mRecognitionCancelled) {
                    try {
                        callback.error(SpeechRecognizer.ERROR_CLIENT);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error sending error", e);
                    }
                }
            }
            
            @Override
            public void onRecognitionStarted() {
                Log.d(TAG, "Recognition started");
            }
            
            @Override
            public void onRecognitionFinished() {
                Log.d(TAG, "Recognition finished");
            }
            
            @Override
            public void onPartialResult(String partialText) {
                // Optional: Send partial results
                if (!mRecognitionCancelled && partialText != null) {
                    Bundle partialResults = new Bundle();
                    ArrayList<String> partialList = new ArrayList<>();
                    partialList.add(partialText);
                    partialResults.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, partialList);
                    
                    try {
                        callback.partialResults(partialResults);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error sending partial results", e);
                    }
                }
            }
        });
        
        // Get audio data and start recognition
        float[] audioData = RecordBuffer.getSamples();
        if (audioData != null && audioData.length > 0) {
            Log.d(TAG, "Starting recognition with " + audioData.length + " samples");
            mRecognitionEngine.recognize(audioData, langCode);
        } else {
            Log.e(TAG, "No audio data available");
            try {
                callback.error(SpeechRecognizer.ERROR_NO_MATCH);
            } catch (RemoteException e) {
                Log.e(TAG, "Error sending no match error", e);
            }
        }
    }
    
    @Override
    protected void onCancel(Callback callback) {
        Log.d(TAG, "onCancel");
        mRecognitionCancelled = true;
        stopRecording();
        if (mRecognitionEngine != null) {
            mRecognitionEngine.cancelRecognition();
        }
    }
    
    @Override
    protected void onStopListening(Callback callback) {
        Log.d(TAG, "onStopListening");
        stopRecording();
    }
    
    private void stopRecording() {
        if (mRecorder != null && mRecorder.isInProgress()) {
            mRecorder.stop();
        }
    }
    
    private boolean checkRecordPermission() {
        return checkPermission(android.Manifest.permission.RECORD_AUDIO, 
                               android.os.Process.myPid(), 
                               android.os.Process.myUid()) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Recognition service destroyed");
        
        if (mRecorder != null) {
            stopRecording();
            mRecorder.cleanup();
            mRecorder = null;
        }
        
        if (mRecognitionEngine != null) {
            mRecognitionEngine.cleanup();
            mRecognitionEngine = null;
        }
        
        RecordBuffer.clear();
    }
}