// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.audio;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.preference.PreferenceManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.konovalov.vad.webrtc.Vad;
import com.konovalov.vad.webrtc.VadWebRTC;
import com.konovalov.vad.webrtc.config.FrameSize;
import com.konovalov.vad.webrtc.config.Mode;
import com.konovalov.vad.webrtc.config.SampleRate;

import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.Defaults;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Audio recorder for voice input.
 * Records audio at 16kHz mono for voice recognition.
 */
public class Recorder {

    public interface RecorderListener {
        void onUpdateReceived(String message);
        default void onAudioBufferReceived(float[] audioBuffer) {} // Optional callback for audio visualization
    }

    private static final String TAG = "VoiceRecorder";
    public static final String MSG_RECORDING = "Recording...";
    public static final String MSG_RECORDING_DONE = "Recording done...!";
    public static final String MSG_RECORDING_ERROR = "Recording error...";
    public static final String MSG_PERMISSION_ERROR = "Microphone permission required";

    private final Context mContext;
    private final AtomicBoolean mInProgress = new AtomicBoolean(false);

    private RecorderListener mListener;
    private final Lock lock = new ReentrantLock();
    private final Condition hasTask = lock.newCondition();
    private final Object fileSavedLock = new Object();

    private volatile boolean shouldStartRecording = false;
    private boolean useVAD = false;
    private VadWebRTC vad = null;
    private static final int VAD_FRAME_SIZE = 480;

    private final Thread workerThread;

    public Recorder(Context context) {
        this.mContext = context.getApplicationContext();

        // Initialize and start the worker thread
        workerThread = new Thread(this::recordLoop);
        workerThread.start();
    }

    public void setListener(RecorderListener listener) {
        this.mListener = listener;
    }

    public void initVad(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        // Read VAD parameters from preferences
        int sensitivity = prefs.getInt(Settings.PREF_VAD_SENSITIVITY, Defaults.PREF_VAD_SENSITIVITY);
        int silenceDuration = prefs.getInt(Settings.PREF_VAD_SILENCE_DURATION, Defaults.PREF_VAD_SILENCE_DURATION);
        int speechDuration = prefs.getInt(Settings.PREF_VAD_SPEECH_DURATION, Defaults.PREF_VAD_SPEECH_DURATION);

        // Convert sensitivity index to Mode enum
        Mode mode = Mode.values()[sensitivity];

        vad = Vad.builder()
                .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                .setFrameSize(FrameSize.FRAME_SIZE_480)
                .setMode(mode)
                .setSilenceDurationMs(silenceDuration)
                .setSpeechDurationMs(speechDuration)
                .build();
        useVAD = true;
        Log.d(TAG, "VAD initialized with mode=" + mode + ", silence=" + silenceDuration + "ms, speech=" + speechDuration + "ms");
    }

    public void start() {
        if (!mInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Recording is already in progress...");
            return;
        }
        lock.lock();
        try {
            Log.d(TAG, "Recording starts now");
            shouldStartRecording = true;
            hasTask.signal();
        } finally {
            lock.unlock();
        }
    }

    public void stop() {
        Log.d(TAG, "Recording stopped");
        mInProgress.set(false);

        // Wait for the recording thread to finish
        synchronized (fileSavedLock) {
            try {
                fileSavedLock.wait(); // Wait until notified by the recording thread
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isInProgress() {
        return mInProgress.get();
    }

    private void sendUpdate(String message) {
        if (mListener != null)
            mListener.onUpdateReceived(message);
    }
    
    private void sendAudioBuffer(byte[] audioData, int bytesRead) {
        if (mListener != null) {
            // Convert byte array to float array for visualization
            float[] floatBuffer = new float[bytesRead / 2]; // 16-bit audio = 2 bytes per sample
            for (int i = 0; i < floatBuffer.length; i++) {
                // Convert 16-bit PCM to float (-1.0 to 1.0)
                short sample = (short) ((audioData[i * 2] & 0xFF) | (audioData[i * 2 + 1] << 8));
                floatBuffer[i] = sample / 32768.0f;
            }
            mListener.onAudioBufferReceived(floatBuffer);
        }
    }

    private void recordLoop() {
        while (true) {
            lock.lock();
            try {
                while (!shouldStartRecording) {
                    hasTask.await();
                }
                shouldStartRecording = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }

            // Start recording process
            try {
                recordAudio();
            } catch (Exception e) {
                Log.e(TAG, "Recording error...", e);
                sendUpdate(MSG_RECORDING_ERROR);
            } finally {
                mInProgress.set(false);
            }
        }
    }

    private void recordAudio() {
        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "AudioRecord permission is not granted");
            sendUpdate(MSG_PERMISSION_ERROR);
            return;
        }

        int channels = 1;
        int bytesPerSample = 2;
        int sampleRateInHz = 16000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;

        int bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        if (bufferSize < VAD_FRAME_SIZE * 2) bufferSize = VAD_FRAME_SIZE * 2; // Ensure buffer supports VAD frame size

        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        
        // Optional: Enable Bluetooth SCO for Bluetooth headset recording
        try {
            audioManager.startBluetoothSco();
            audioManager.setBluetoothScoOn(true);
        } catch (Exception e) {
            Log.w(TAG, "Bluetooth SCO not available", e);
        }

        AudioRecord audioRecord = null;
        try {
            AudioRecord.Builder builder = new AudioRecord.Builder()
                    .setAudioSource(audioSource)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setChannelMask(channelConfig)
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRateInHz)
                            .build())
                    .setBufferSizeInBytes(bufferSize);

            audioRecord = builder.build();
            audioRecord.startRecording();

            // Calculate maximum byte counts for 30 seconds
            int bytesForThirtySeconds = sampleRateInHz * bytesPerSample * channels * 30;

            ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();

            byte[] audioData = new byte[bufferSize];
            int totalBytesRead = 0;

            // VAD state tracking
            boolean isSpeech;
            boolean isRecording = false;
            byte[] vadAudioBuffer = new byte[VAD_FRAME_SIZE * 2];  // VAD needs 16-bit samples

            // For audio visualization
            long lastVisualizationUpdate = 0;
            final long VISUALIZATION_UPDATE_INTERVAL = 33; // ~30fps for smooth animation

            boolean hasNotifiedRecording = false;

            while (mInProgress.get() && totalBytesRead < bytesForThirtySeconds) {
                int bytesRead = audioRecord.read(audioData, 0, VAD_FRAME_SIZE * 2);
                if (bytesRead > 0) {
                    outputBuffer.write(audioData, 0, bytesRead);
                    totalBytesRead += bytesRead;

                    // Send audio buffer for visualization
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastVisualizationUpdate > VISUALIZATION_UPDATE_INTERVAL) {
                        sendAudioBuffer(audioData, bytesRead);
                        lastVisualizationUpdate = currentTime;
                    }
                } else {
                    Log.d(TAG, "AudioRecord error, bytes read: " + bytesRead);
                    break;
                }

                // VAD processing
                if (useVAD) {
                    byte[] outputBufferByteArray = outputBuffer.toByteArray();
                    if (outputBufferByteArray.length >= VAD_FRAME_SIZE * 2) {
                        // Always use the last VAD_FRAME_SIZE * 2 bytes (16-bit) from outputBuffer for VAD
                        System.arraycopy(outputBufferByteArray, outputBufferByteArray.length - VAD_FRAME_SIZE * 2, vadAudioBuffer, 0, VAD_FRAME_SIZE * 2);

                        isSpeech = vad.isSpeech(vadAudioBuffer);
                        if (isSpeech) {
                            if (!isRecording) {
                                Log.d(TAG, "VAD Speech detected: recording starts");
                                sendUpdate(MSG_RECORDING);
                            }
                            isRecording = true;
                        } else {
                            if (isRecording) {
                                Log.d(TAG, "VAD Silence detected: stopping recording");
                                isRecording = false;
                                mInProgress.set(false);
                            }
                        }
                    }
                } else {
                    if (!hasNotifiedRecording) {
                        sendUpdate(MSG_RECORDING);
                        hasNotifiedRecording = true;
                    }
                }
            }
            
            Log.d(TAG, "Total bytes recorded: " + totalBytesRead);

            // Clean up VAD if it was used
            if (useVAD) {
                useVAD = false;
                if (vad != null) {
                    vad.close();
                    vad = null;
                }
                Log.d(TAG, "Closing VAD");
            }

            // Save recorded audio data to BufferStore (up to 30 seconds)
            RecordBuffer.setOutputBuffer(outputBuffer.toByteArray());

            if (totalBytesRead > 6400) {  // min 0.2s of audio
                sendUpdate(MSG_RECORDING_DONE);
            } else {
                sendUpdate(MSG_RECORDING_ERROR);
            }

        } finally {
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
            }
            
            try {
                audioManager.stopBluetoothSco();
                audioManager.setBluetoothScoOn(false);
            } catch (Exception e) {
                Log.w(TAG, "Error stopping Bluetooth SCO", e);
            }

            // Notify the waiting thread that recording is complete
            synchronized (fileSavedLock) {
                fileSavedLock.notify();
            }
        }
    }
    
    public void cleanup() {
        mInProgress.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }
}