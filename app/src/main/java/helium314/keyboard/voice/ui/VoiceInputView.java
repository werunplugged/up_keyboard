// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import helium314.keyboard.latin.R;
import helium314.keyboard.voice.audio.RecordBuffer;
import helium314.keyboard.voice.audio.Recorder;
import helium314.keyboard.voice.recognition.PlaceholderRecognitionEngine;
import helium314.keyboard.voice.recognition.VoiceRecognitionEngine;

import android.inputmethodservice.InputMethodService;
import android.view.inputmethod.InputConnection;

/**
 * Voice input view for HeliBoard keyboard.
 * Provides UI for voice recording and handles recognition through pluggable engines.
 */
public class VoiceInputView extends FrameLayout {
    private static final String TAG = "VoiceInputView";
    private static final long RECORDING_MAX_TIME = 30000; // 30 seconds
    private static final long SUCCESS_ANIMATION_DURATION = 100;
    
    // UI Components
    private WaveformView waveformView;
    private TextView statusText;
    private TextView partialTranscriptionText;
    private ImageView stopButton;
    private CircularProgressView countdownTimer;
    private ImageView successCheckmark;
    private ProgressBar processingSpinner;
    
    // Core Components
    private Recorder mRecorder = null;
    private VoiceRecognitionEngine recognitionEngine = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable = null;
    
    // State
    private boolean isProcessing = false;
    private boolean isRecording = false;
    
    // Recognition parameters
    private String languageHint;
    
    // IME Support
    private InputMethodService inputMethodService = null;
    private boolean autoStartRecording = false;
    private boolean autoCommitAndSwitch = false;
    
    // Listener
    private OnVoiceResultListener listener;
    
    public interface OnVoiceResultListener {
        void onResult(String text, String languageCode);
        void onError(String error);
        void onRecordingStarted();
        void onRecordingStopped();
    }
    
    public VoiceInputView(Context context) {
        super(context);
        init(context);
    }
    
    public VoiceInputView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }
    
    public VoiceInputView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }
    
    private void init(Context context) {
        // Inflate the layout
        LayoutInflater.from(context).inflate(R.layout.voice_input_view, this, true);
        
        // Set layout params for this view
        setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        
        // Initialize UI components
        waveformView = findViewById(R.id.waveform_view);
        statusText = findViewById(R.id.status_text);
        partialTranscriptionText = findViewById(R.id.partial_transcription_text);
        stopButton = findViewById(R.id.stop_button);
        countdownTimer = findViewById(R.id.countdown_timer);
        successCheckmark = findViewById(R.id.success_checkmark);
        processingSpinner = findViewById(R.id.processing_spinner);
        
        // Clear any stale partial transcription text
        partialTranscriptionText.setText("");
        partialTranscriptionText.setVisibility(View.GONE);
        
        // Set up stop button
        stopButton.setOnClickListener(v -> stopRecording());
        
        // Initialize components
        initializeRecorder();
        initializeRecognitionEngine();
    }
    
    private void initializeRecorder() {
        mRecorder = new Recorder(getContext());
        mRecorder.setListener(new Recorder.RecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                handler.post(() -> {
                    if (message.equals(Recorder.MSG_RECORDING)) {
                        Log.d(TAG, "Recording started");
                    } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                        Log.d(TAG, "Recording done, starting transcription");
                        onRecordingComplete();
                    } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                        Log.e(TAG, "Recording error");
                        showError(getContext().getString(R.string.voice_recording_error));
                    } else if (message.equals(Recorder.MSG_PERMISSION_ERROR)) {
                        showPermissionError();
                    }
                });
            }
            
            @Override
            public void onAudioBufferReceived(float[] audioBuffer) {
                handler.post(() -> {
                    if (waveformView != null && isRecording) {
                        waveformView.updateAmplitude(audioBuffer);
                    }
                });
            }
        });
    }
    
    private void initializeRecognitionEngine() {
        // Use placeholder engine for now
        // TODO: Replace with actual recognition engine when available
        recognitionEngine = new PlaceholderRecognitionEngine(getContext());
        recognitionEngine.initialize();
        recognitionEngine.setListener(new VoiceRecognitionEngine.RecognitionListener() {
            @Override
            public void onRecognitionResult(String text, String languageCode) {
                handler.post(() -> {
                    Log.d(TAG, "Recognition result: " + text);
                    Log.d(TAG, "Language: " + languageCode);
                    showSuccessAndNotify(text, languageCode);
                });
            }
            
            @Override
            public void onRecognitionError(String error) {
                handler.post(() -> {
                    Log.e(TAG, "Recognition error: " + error);
                    showError(error);
                });
            }
            
            @Override
            public void onRecognitionStarted() {
                handler.post(() -> {
                    Log.d(TAG, "Recognition started");
                    statusText.setText(getContext().getString(R.string.voice_processing));
                });
            }
            
            @Override
            public void onRecognitionFinished() {
                handler.post(() -> {
                    Log.d(TAG, "Recognition finished");
                });
            }
            
            @Override
            public void onPartialResult(String partialText) {
                // Show partial results in UI
                handler.post(() -> {
                    if (partialText != null && !partialText.trim().isEmpty()) {
                        partialTranscriptionText.setText(partialText);
                        partialTranscriptionText.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }
    
    private void showPermissionError() {
        statusText.setText(getContext().getString(R.string.voice_permission_required));
        waveformView.setState(WaveformView.State.ERROR);
        
        if (listener != null) {
            listener.onError(getContext().getString(R.string.voice_permission_required));
        }
    }
    
    public void setOnVoiceResultListener(OnVoiceResultListener listener) {
        this.listener = listener;
    }
    
    // IME-specific methods
    public void setInputMethodService(InputMethodService ims) {
        this.inputMethodService = ims;
        this.autoCommitAndSwitch = (ims != null);
    }
    
    public void setAutoStartRecording(boolean autoStart) {
        this.autoStartRecording = autoStart;
        if (autoStart && !isRecording && !isProcessing) {
            // Start recording after a short delay to allow UI to be ready
            handler.postDelayed(this::startRecording, 100);
        }
    }
    
    /**
     * Sets language hint for recognition
     * @param language Language hint (e.g., "en", "es", "fr") or null for auto-detection
     */
    public void setLanguageHint(String language) {
        this.languageHint = language;
        Log.d(TAG, "Language hint set: " + language);
    }
    
    /**
     * Set custom recognition engine
     * @param engine Recognition engine implementation
     */
    public void setRecognitionEngine(VoiceRecognitionEngine engine) {
        if (recognitionEngine != null) {
            recognitionEngine.cleanup();
        }
        this.recognitionEngine = engine;
        if (engine != null) {
            engine.initialize();
            engine.setListener(new VoiceRecognitionEngine.RecognitionListener() {
                @Override
                public void onRecognitionResult(String text, String languageCode) {
                    handler.post(() -> showSuccessAndNotify(text, languageCode));
                }
                
                @Override
                public void onRecognitionError(String error) {
                    handler.post(() -> showError(error));
                }
                
                @Override
                public void onRecognitionStarted() {
                    handler.post(() -> statusText.setText(getContext().getString(R.string.voice_processing)));
                }
                
                @Override
                public void onRecognitionFinished() {
                    // Optional
                }
                
                @Override
                public void onPartialResult(String partialText) {
                    // Show partial results in UI
                    handler.post(() -> {
                        Log.d(TAG, "Updating UI with partial result: " + partialText);
                        if (partialText != null && !partialText.trim().isEmpty()) {
                            partialTranscriptionText.setText(partialText);
                            partialTranscriptionText.setVisibility(View.VISIBLE);
                        }
                    });
                }
            });
        }
    }
    
    private void commitTextAndSwitch(String text) {
        Log.d(TAG, "commitTextAndSwitch: " + text);
        
        if (inputMethodService != null) {
            InputConnection ic = inputMethodService.getCurrentInputConnection();
            
            if (ic != null && text != null && !text.trim().isEmpty()) {
                ic.commitText(text.trim() + " ", 1);
            }
            
            // Switch back to previous input method after a short delay
            handler.postDelayed(() -> {
                inputMethodService.switchToPreviousInputMethod();
            }, 100);
        }
    }
    
    public void startRecording() {
        // Cancel any pending timeout from previous session
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
        
        // Clear any stale partial transcription
        partialTranscriptionText.setText("");
        partialTranscriptionText.setVisibility(View.GONE);
        
        if (!checkRecordPermission()) {
            showPermissionError();
            return;
        }
        
        if (isRecording || isProcessing) {
            Log.d(TAG, "Already recording or processing");
            return;
        }
        
        isRecording = true;
        
        // Provide haptic feedback
        Vibrator vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
        }
        
        // Reset UI
        statusText.setText(getContext().getString(R.string.voice_listening));
        successCheckmark.setVisibility(View.GONE);
        processingSpinner.setVisibility(View.GONE);
        
        // Start waveform animation
        waveformView.setState(WaveformView.State.LISTENING);
        waveformView.setVisibility(View.VISIBLE);
        
        // Show stop button with animation
        stopButton.setAlpha(0f);
        stopButton.setVisibility(View.VISIBLE);
        stopButton.animate()
            .alpha(1f)
            .setDuration(200)
            .start();
        
        // Start countdown timer
        countdownTimer.setVisibility(View.VISIBLE);
        countdownTimer.startCountdown(RECORDING_MAX_TIME);
        
        // Auto-stop after max time
        handler.postDelayed(() -> {
            if (isRecording) {
                stopRecording();
            }
        }, RECORDING_MAX_TIME);
        
        // Start recording
        if (mRecorder != null) {
            mRecorder.start();
        }
        
        if (listener != null) {
            listener.onRecordingStarted();
        }
    }
    
    public void stopRecording() {
        if (!isRecording) return;
        
        isRecording = false;
        
        // Stop recording
        if (mRecorder != null && mRecorder.isInProgress()) {
            mRecorder.stop();
        }
        
        // Update UI
        waveformView.setState(WaveformView.State.IDLE);
        countdownTimer.stopCountdown();
        countdownTimer.setVisibility(View.GONE);
        
        // Clear partial transcription
        partialTranscriptionText.setText("");
        partialTranscriptionText.setVisibility(View.GONE);
        
        // Hide stop button
        stopButton.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction(() -> stopButton.setVisibility(View.GONE))
            .start();
        
        if (listener != null) {
            listener.onRecordingStopped();
        }
    }
    
    private void onRecordingComplete() {
        isRecording = false;
        isProcessing = true;
        
        // Update UI for processing
        handler.post(() -> {
            statusText.setText(getContext().getString(R.string.voice_processing));
            waveformView.setState(WaveformView.State.PROCESSING);
            processingSpinner.setVisibility(View.VISIBLE);
            countdownTimer.setVisibility(View.GONE);
            stopButton.setVisibility(View.GONE);
            // Keep partial transcription visible but clear it initially
            partialTranscriptionText.setText("");
            partialTranscriptionText.setVisibility(View.VISIBLE);
        });
        
        // Start transcription
        startTranscription();
    }
    
    private void startTranscription() {
        if (recognitionEngine != null) {
            // Get audio data from record buffer
            float[] audioData = RecordBuffer.getSamples();
            if (audioData != null && audioData.length > 0) {
                Log.d(TAG, "Starting recognition with " + audioData.length + " samples");
                recognitionEngine.recognize(audioData, languageHint);
            } else {
                Log.e(TAG, "No audio data available");
                showError(getContext().getString(R.string.voice_no_audio));
            }
            
            // Cancel any existing timeout
            if (timeoutRunnable != null) {
                handler.removeCallbacks(timeoutRunnable);
            }
            
            // Add timeout to prevent stuck state
            timeoutRunnable = () -> {
                if (isProcessing) {
                    Log.w(TAG, "Processing timeout after 15 seconds");
                    showError(getContext().getString(R.string.voice_recognition_error));
                }
            };
            handler.postDelayed(timeoutRunnable, 15000);
        } else {
            Log.e(TAG, "No recognition engine available");
            showError(getContext().getString(R.string.voice_engine_unavailable));
        }
    }
    
    private void showSuccessAndNotify(String text, String language) {
        isProcessing = false;
        
        // Cancel timeout since we got a result
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
        
        Log.d(TAG, "Recognition success: " + text);
        
        // Check if result is empty
        if (text == null || text.trim().isEmpty()) {
            showError(getContext().getString(R.string.voice_no_speech));
            return;
        }
        
        // Provide haptic feedback
        Vibrator vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
        }
        
        // Show success animation
        waveformView.setState(WaveformView.State.SUCCESS);
        processingSpinner.setVisibility(View.GONE);
        
        // Animate success checkmark
        successCheckmark.setScaleX(0f);
        successCheckmark.setScaleY(0f);
        successCheckmark.setVisibility(View.VISIBLE);
        
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(successCheckmark, "scaleX", 0f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(successCheckmark, "scaleY", 0f, 1f);
        
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(scaleX, scaleY);
        animatorSet.setDuration(SUCCESS_ANIMATION_DURATION);
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // In IME mode, commit text and switch automatically
                if (autoCommitAndSwitch) {
                    commitTextAndSwitch(text);
                }
                
                // Notify listener with result
                if (listener != null) {
                    listener.onResult(text.trim(), language);
                }
            }
        });
        animatorSet.start();
        
        statusText.setText(getContext().getString(R.string.voice_success));
    }
    
    private void showError(String error) {
        isRecording = false;
        isProcessing = false;
        
        // Cancel timeout if it exists
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
        
        // Update UI
        waveformView.setState(WaveformView.State.ERROR);
        statusText.setText(error);
        processingSpinner.setVisibility(View.GONE);
        countdownTimer.setVisibility(View.GONE);
        stopButton.setVisibility(View.GONE);
        // Keep partial transcription visible in case results are still coming
        // partialTranscriptionText visibility is not changed
        
        // In IME mode, switch back to previous keyboard after error
        if (autoCommitAndSwitch && inputMethodService != null) {
            handler.postDelayed(() -> {
                inputMethodService.switchToPreviousInputMethod();
            }, 1500);
        }
        
        // Notify listener
        if (listener != null) {
            listener.onError(error);
        }
    }
    
    private boolean checkRecordPermission() {
        int permission = ContextCompat.checkSelfPermission(getContext(), 
            android.Manifest.permission.RECORD_AUDIO);
        return (permission == PackageManager.PERMISSION_GRANTED);
    }
    
    public void cleanup() {
        if (recognitionEngine != null) {
            recognitionEngine.cleanup();
            recognitionEngine = null;
        }
        if (mRecorder != null) {
            if (mRecorder.isInProgress()) {
                mRecorder.stop();
            }
            mRecorder.cleanup();
        }
        RecordBuffer.clear();
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cleanup();
    }
}