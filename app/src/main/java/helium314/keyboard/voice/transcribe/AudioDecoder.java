// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.transcribe;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decodes audio files (MP3, OGG/Opus, AAC/M4A, WAV, AMR) to 16kHz mono float[] for Whisper.
 */
public class AudioDecoder {
    private static final String TAG = "AudioDecoder";
    private static final int TARGET_SAMPLE_RATE = 16000;
    private static final int MAX_DURATION_SECONDS = 60;
    private static final int MAX_SAMPLES = TARGET_SAMPLE_RATE * MAX_DURATION_SECONDS;

    /**
     * Decode an audio URI to 16kHz mono float samples, normalized for Whisper.
     * Truncates to 60 seconds.
     */
    public static float[] decode(Context context, Uri uri) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);

            int audioTrack = selectAudioTrack(extractor);
            if (audioTrack < 0) {
                throw new IOException("No audio track found");
            }
            extractor.selectTrack(audioTrack);

            MediaFormat format = extractor.getTrackFormat(audioTrack);
            String mime = format.getString(MediaFormat.KEY_MIME);
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            Log.d(TAG, "Audio: mime=" + mime + " rate=" + sampleRate + " channels=" + channels);

            // Decode to PCM 16-bit
            short[] pcm = decodeToShort(extractor, format, mime);
            Log.d(TAG, "Decoded " + pcm.length + " PCM samples");

            // Mix to mono if stereo
            if (channels > 1) {
                pcm = mixToMono(pcm, channels);
            }

            // Resample to 16kHz
            float[] samples;
            if (sampleRate != TARGET_SAMPLE_RATE) {
                samples = resample(pcm, sampleRate, TARGET_SAMPLE_RATE);
            } else {
                samples = shortToFloat(pcm);
            }

            // Truncate to 60s
            if (samples.length > MAX_SAMPLES) {
                float[] truncated = new float[MAX_SAMPLES];
                System.arraycopy(samples, 0, truncated, 0, MAX_SAMPLES);
                samples = truncated;
                Log.d(TAG, "Truncated to " + MAX_DURATION_SECONDS + "s");
            }

            // Normalize
            normalize(samples);

            Log.d(TAG, "Final samples: " + samples.length + " (" + (samples.length / (float) TARGET_SAMPLE_RATE) + "s)");
            return samples;
        } finally {
            extractor.release();
        }
    }

    private static int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    private static short[] decodeToShort(MediaExtractor extractor, MediaFormat format, String mime) throws IOException {
        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        try {
            codec.configure(format, null, null, 0);
            codec.start();

            ByteBuffer[] inputBuffers = codec.getInputBuffers();
            ByteBuffer[] outputBuffers = codec.getOutputBuffers();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            // Estimate output size (generous)
            int estimatedSamples = TARGET_SAMPLE_RATE * MAX_DURATION_SECONDS * 2; // overestimate
            short[] output = new short[estimatedSamples];
            int outputPos = 0;

            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = inputBuffers[inputIndex];
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, pts, 0);
                            extractor.advance();
                        }
                    }
                }

                // Drain output
                int outputIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outputIndex >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }

                    ByteBuffer outBuf = outputBuffers[outputIndex];
                    outBuf.position(info.offset);
                    outBuf.limit(info.offset + info.size);
                    outBuf.order(ByteOrder.nativeOrder());

                    int shortCount = info.size / 2;
                    // Grow array if needed
                    if (outputPos + shortCount > output.length) {
                        short[] newOutput = new short[Math.max(output.length * 2, outputPos + shortCount)];
                        System.arraycopy(output, 0, newOutput, 0, outputPos);
                        output = newOutput;
                    }
                    for (int i = 0; i < shortCount; i++) {
                        output[outputPos++] = outBuf.getShort();
                    }

                    codec.releaseOutputBuffer(outputIndex, false);
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = codec.getOutputBuffers();
                }
            }

            // Trim to actual size
            short[] result = new short[outputPos];
            System.arraycopy(output, 0, result, 0, outputPos);
            return result;
        } finally {
            codec.stop();
            codec.release();
        }
    }

    private static short[] mixToMono(short[] pcm, int channels) {
        int monoLength = pcm.length / channels;
        short[] mono = new short[monoLength];
        for (int i = 0; i < monoLength; i++) {
            int sum = 0;
            for (int ch = 0; ch < channels; ch++) {
                sum += pcm[i * channels + ch];
            }
            mono[i] = (short) (sum / channels);
        }
        return mono;
    }

    /** Linear interpolation resampling */
    private static float[] resample(short[] pcm, int srcRate, int dstRate) {
        double ratio = (double) srcRate / dstRate;
        int outLength = (int) (pcm.length / ratio);
        float[] output = new float[outLength];
        for (int i = 0; i < outLength; i++) {
            double srcPos = i * ratio;
            int idx = (int) srcPos;
            double frac = srcPos - idx;
            if (idx + 1 < pcm.length) {
                output[i] = (float) ((1.0 - frac) * pcm[idx] + frac * pcm[idx + 1]) / 32768.0f;
            } else if (idx < pcm.length) {
                output[i] = pcm[idx] / 32768.0f;
            }
        }
        return output;
    }

    private static float[] shortToFloat(short[] pcm) {
        float[] out = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            out[i] = pcm[i] / 32768.0f;
        }
        return out;
    }

    /** Normalize samples like RecordBuffer.getSamples() */
    private static void normalize(float[] samples) {
        float maxAbs = 0.0f;
        for (float s : samples) {
            float abs = Math.abs(s);
            if (abs > maxAbs) maxAbs = abs;
        }
        if (maxAbs > 0.0f) {
            for (int i = 0; i < samples.length; i++) {
                samples[i] /= maxAbs;
            }
        }
    }
}
