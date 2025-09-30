// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/**
 * Circular progress view for countdown timer during voice recording.
 */
public class CircularProgressView extends View {
    private Paint progressPaint;
    private Paint backgroundPaint;
    private RectF progressRect;
    private float progress = 0f;
    private float maxProgress = 100f;
    private ValueAnimator progressAnimator;
    private long duration = 30000; // 30 seconds default
    
    public CircularProgressView(Context context) {
        super(context);
        init();
    }
    
    public CircularProgressView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public CircularProgressView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(4f);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setColor(0xFF4285F4); // Blue color
        
        backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backgroundPaint.setStyle(Paint.Style.STROKE);
        backgroundPaint.setStrokeWidth(4f);
        backgroundPaint.setColor(0xFF757575); // Gray color
        backgroundPaint.setAlpha(50);
        
        progressRect = new RectF();
    }
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float padding = progressPaint.getStrokeWidth() / 2;
        progressRect.set(padding, padding, w - padding, h - padding);
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        // Draw background circle
        canvas.drawArc(progressRect, 0, 360, false, backgroundPaint);
        
        // Draw progress arc (starting from top)
        float sweepAngle = (progress / maxProgress) * 360f;
        canvas.drawArc(progressRect, -90, sweepAngle, false, progressPaint);
    }
    
    public void startCountdown(long durationMs) {
        this.duration = durationMs;
        progress = maxProgress;
        
        if (progressAnimator != null) {
            progressAnimator.cancel();
        }
        
        progressAnimator = ValueAnimator.ofFloat(maxProgress, 0);
        progressAnimator.setDuration(duration);
        progressAnimator.setInterpolator(new LinearInterpolator());
        progressAnimator.addUpdateListener(animation -> {
            progress = (float) animation.getAnimatedValue();
            invalidate();
        });
        progressAnimator.start();
    }
    
    public void stopCountdown() {
        if (progressAnimator != null) {
            progressAnimator.cancel();
            progressAnimator = null;
        }
        progress = 0;
        invalidate();
    }
    
    public void setProgress(float progress) {
        this.progress = Math.max(0, Math.min(maxProgress, progress));
        invalidate();
    }
    
    public void setProgressColor(int color) {
        progressPaint.setColor(color);
        invalidate();
    }
    
    public void setBackgroundColor(int color) {
        backgroundPaint.setColor(color);
        invalidate();
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (progressAnimator != null) {
            progressAnimator.cancel();
            progressAnimator = null;
        }
    }
}