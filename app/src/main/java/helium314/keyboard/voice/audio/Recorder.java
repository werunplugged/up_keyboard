// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.audio;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.app.ActivityCompat;

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
        if (bufferSize < 960) bufferSize = 960; // Minimum reasonable buffer

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
            
            // For audio visualization
            long lastVisualizationUpdate = 0;
            final long VISUALIZATION_UPDATE_INTERVAL = 33; // ~30fps for smooth animation
            
            boolean hasNotifiedRecording = false;

            while (mInProgress.get() && totalBytesRead < bytesForThirtySeconds) {
                int bytesRead = audioRecord.read(audioData, 0, audioData.length);
                if (bytesRead > 0) {
                    outputBuffer.write(audioData, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    
                    if (!hasNotifiedRecording) {
                        sendUpdate(MSG_RECORDING);
                        hasNotifiedRecording = true;
                    }
                    
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
            }
            
            Log.d(TAG, "Total bytes recorded: " + totalBytesRead);

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