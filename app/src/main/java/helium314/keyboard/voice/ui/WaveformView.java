// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import helium314.keyboard.latin.R;

import java.util.Random;

/**
 * Waveform visualization view for voice input.
 * Shows animated bars that respond to audio amplitude.
 */
public class WaveformView extends View {
    private static final int BAR_COUNT = 30;
    private static final float BAR_WIDTH_RATIO = 0.6f;
    private static final float MIN_BAR_HEIGHT = 0.05f;
    private static final float SMOOTHING_FACTOR = 0.85f;
    private static final float AUDIO_THRESHOLD = 0.01f;
    
    private Paint barPaint;
    private Paint glowPaint;
    private float[] amplitudes;
    private float[] targetAmplitudes;
    private float[] displayAmplitudes;
    private ValueAnimator amplitudeAnimator;
    private ValueAnimator pulseAnimator;
    private int primaryColor;
    private int glowColor;
    private boolean isProcessing = false;
    private boolean isReceivingAudio = false;
    private long lastAudioUpdateTime = 0;
    private Random random = new Random();
    
    // Animation states
    public enum State {
        IDLE,
        LISTENING,
        PROCESSING,
        SUCCESS,
        ERROR
    }
    
    private State currentState = State.IDLE;
    
    public WaveformView(Context context) {
        super(context);
        init();
    }
    
    public WaveformView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public WaveformView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        amplitudes = new float[BAR_COUNT];
        targetAmplitudes = new float[BAR_COUNT];
        displayAmplitudes = new float[BAR_COUNT];
        
        barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        barPaint.setStyle(Paint.Style.FILL);
        
        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setAlpha(100);
        
        // Use theme colors - will be updated to use actual theme colors
        primaryColor = 0xFF4285F4; // Default blue
        glowColor = primaryColor;
        
        // Initialize with small random values for idle animation
        for (int i = 0; i < BAR_COUNT; i++) {
            amplitudes[i] = MIN_BAR_HEIGHT + random.nextFloat() * 0.1f;
            displayAmplitudes[i] = amplitudes[i];
        }
        
        startAnimation();
    }
    
    private void startAnimation() {
        if (amplitudeAnimator != null) {
            amplitudeAnimator.cancel();
        }
        
        amplitudeAnimator = ValueAnimator.ofFloat(0f, 1f);
        amplitudeAnimator.setDuration(33); // 30fps
        amplitudeAnimator.setRepeatCount(ValueAnimator.INFINITE);
        amplitudeAnimator.setInterpolator(new DecelerateInterpolator());
        amplitudeAnimator.addUpdateListener(animation -> {
            updateDisplayAmplitudes();
            invalidate();
        });
        amplitudeAnimator.start();
    }
    
    private void updateDisplayAmplitudes() {
        for (int i = 0; i < BAR_COUNT; i++) {
            // Smooth interpolation
            displayAmplitudes[i] = displayAmplitudes[i] * SMOOTHING_FACTOR + 
                                   targetAmplitudes[i] * (1f - SMOOTHING_FACTOR);
            
            // Add idle animation
            if (currentState == State.IDLE || currentState == State.PROCESSING) {
                float idleWave = (float) Math.sin(System.currentTimeMillis() * 0.001 + i * 0.5) * 0.05f;
                displayAmplitudes[i] += idleWave;
            }
            
            // Clamp values
            displayAmplitudes[i] = Math.max(MIN_BAR_HEIGHT, Math.min(1f, displayAmplitudes[i]));
        }
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        int width = getWidth();
        int height = getHeight();
        
        if (width == 0 || height == 0) return;
        
        float barWidth = (float) width / BAR_COUNT;
        float barSpacing = barWidth * (1f - BAR_WIDTH_RATIO);
        float actualBarWidth = barWidth - barSpacing;
        
        updatePaintColors();
        
        // Draw bars
        for (int i = 0; i < BAR_COUNT; i++) {
            float barHeight = displayAmplitudes[i] * height * 0.8f;
            float x = i * barWidth + barSpacing / 2;
            float centerY = height / 2f;
            
            // Draw glow for tall bars
            if (displayAmplitudes[i] > 0.5f && currentState == State.LISTENING) {
                RectF glowRect = new RectF(
                    x - 2,
                    centerY - barHeight / 2 - 4,
                    x + actualBarWidth + 2,
                    centerY + barHeight / 2 + 4
                );
                canvas.drawRoundRect(glowRect, actualBarWidth / 2, actualBarWidth / 2, glowPaint);
            }
            
            // Draw main bar
            RectF barRect = new RectF(
                x,
                centerY - barHeight / 2,
                x + actualBarWidth,
                centerY + barHeight / 2
            );
            canvas.drawRoundRect(barRect, actualBarWidth / 2, actualBarWidth / 2, barPaint);
        }
    }
    
    private void updatePaintColors() {
        int color;
        int alpha = 255;
        
        switch (currentState) {
            case LISTENING:
                color = 0xFF4285F4; // Blue
                break;
            case PROCESSING:
                color = 0xFFFFA726; // Amber
                alpha = 150;
                break;
            case SUCCESS:
                color = 0xFF66BB6A; // Green
                break;
            case ERROR:
                color = 0xFFEF5350; // Red
                break;
            default:
                color = 0xFF757575; // Gray
                alpha = 100;
                break;
        }
        
        barPaint.setColor(color);
        barPaint.setAlpha(alpha);
        
        glowPaint.setColor(color);
        glowPaint.setAlpha(alpha / 3);
        
        // Add gradient effect
        LinearGradient gradient = new LinearGradient(
            0, 0, 0, getHeight(),
            new int[]{color, adjustAlpha(color, 150), adjustAlpha(color, 50)},
            new float[]{0f, 0.5f, 1f},
            Shader.TileMode.CLAMP
        );
        barPaint.setShader(gradient);
    }
    
    private int adjustAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
    
    public void updateAmplitude(float[] audioBuffer) {
        if (audioBuffer == null || audioBuffer.length == 0) return;
        
        // Cancel pulse animation when real audio arrives
        if (pulseAnimator != null && pulseAnimator.isRunning()) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        
        // Calculate amplitude for each bar
        int samplesPerBar = audioBuffer.length / BAR_COUNT;
        float maxAmplitude = 0;
        
        for (int i = 0; i < BAR_COUNT; i++) {
            float sum = 0;
            int startIndex = i * samplesPerBar;
            int endIndex = Math.min(startIndex + samplesPerBar, audioBuffer.length);
            
            for (int j = startIndex; j < endIndex; j++) {
                sum += Math.abs(audioBuffer[j]);
            }
            
            float amplitude = sum / samplesPerBar;
            maxAmplitude = Math.max(maxAmplitude, amplitude);
            
            // Normalize and apply logarithmic scaling
            amplitude = (float) (Math.log10(1 + amplitude * 9) * 2);
            targetAmplitudes[i] = Math.min(1f, amplitude);
        }
        
        // Track if we're receiving real audio
        if (maxAmplitude > AUDIO_THRESHOLD) {
            isReceivingAudio = true;
            lastAudioUpdateTime = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - lastAudioUpdateTime > 1000) {
            isReceivingAudio = false;
        }
    }
    
    public void setState(State state) {
        this.currentState = state;
        
        switch (state) {
            case IDLE:
                for (int i = 0; i < BAR_COUNT; i++) {
                    targetAmplitudes[i] = MIN_BAR_HEIGHT + random.nextFloat() * 0.1f;
                }
                break;
            case LISTENING:
                isReceivingAudio = false;
                lastAudioUpdateTime = System.currentTimeMillis();
                animateListeningPulse();
                break;
            case PROCESSING:
                isProcessing = true;
                animateProcessingWave();
                break;
            case SUCCESS:
                animateSuccess();
                break;
            case ERROR:
                animateError();
                break;
        }
        
        invalidate();
    }
    
    private void animateListeningPulse() {
        if (pulseAnimator != null && pulseAnimator.isRunning()) {
            pulseAnimator.cancel();
        }
        
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(2000);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.addUpdateListener(animation -> {
            if (currentState != State.LISTENING || isReceivingAudio) {
                animation.cancel();
                return;
            }
            
            float progress = (float) animation.getAnimatedValue();
            float pulse = 0.1f + progress * 0.05f;
            
            for (int i = 0; i < BAR_COUNT; i++) {
                float distanceFromCenter = Math.abs(i - BAR_COUNT / 2f) / (BAR_COUNT / 2f);
                float centerWeight = 1f - distanceFromCenter * 0.8f;
                targetAmplitudes[i] = MIN_BAR_HEIGHT + pulse * centerWeight;
            }
        });
        pulseAnimator.start();
    }
    
    private void animateProcessingWave() {
        ValueAnimator waveAnimator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        waveAnimator.setDuration(1500);
        waveAnimator.setRepeatCount(ValueAnimator.INFINITE);
        waveAnimator.addUpdateListener(animation -> {
            if (!isProcessing) {
                animation.cancel();
                return;
            }
            
            float phase = (float) animation.getAnimatedValue();
            for (int i = 0; i < BAR_COUNT; i++) {
                targetAmplitudes[i] = 0.3f + (float) Math.sin(phase + i * 0.3) * 0.2f;
            }
        });
        waveAnimator.start();
    }
    
    private void animateSuccess() {
        for (int i = 0; i < BAR_COUNT; i++) {
            targetAmplitudes[i] = 0.8f;
        }
        
        postDelayed(() -> {
            for (int i = 0; i < BAR_COUNT; i++) {
                targetAmplitudes[i] = MIN_BAR_HEIGHT;
            }
        }, 200);
    }
    
    private void animateError() {
        ValueAnimator shakeAnimator = ValueAnimator.ofFloat(-1f, 1f);
        shakeAnimator.setDuration(100);
        shakeAnimator.setRepeatCount(3);
        shakeAnimator.setRepeatMode(ValueAnimator.REVERSE);
        shakeAnimator.addUpdateListener(animation -> {
            float offset = (float) animation.getAnimatedValue() * 0.1f;
            for (int i = 0; i < BAR_COUNT; i++) {
                targetAmplitudes[i] = 0.3f + offset;
            }
        });
        shakeAnimator.start();
    }
    
    public void stopProcessing() {
        isProcessing = false;
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (amplitudeAnimator != null) {
            amplitudeAnimator.cancel();
            amplitudeAnimator = null;
        }
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
    }
}