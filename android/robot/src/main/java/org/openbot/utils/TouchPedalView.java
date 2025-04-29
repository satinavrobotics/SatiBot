package org.openbot.utils;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class TouchPedalView extends View {
    private static final int DEFAULT_COLOR = Color.parseColor("#3F51B5");
    private static final int ACTIVE_COLOR = Color.parseColor("#FF4081");
    private static final int BORDER_COLOR = Color.parseColor("#303F9F");
    private static final float BORDER_WIDTH = 4f;
    private static final float CORNER_RADIUS = 24f;

    private Paint fillPaint;
    private Paint borderPaint;
    private Paint indicatorPaint;
    private RectF pedalRect;
    private float currentValue = 0f; // Range: -1 to 1
    private boolean isTouching = false;
    private OnValueChangeListener valueChangeListener;

    public interface OnValueChangeListener {
        void onValueChanged(float value);
        void onTouchEnd();
    }

    public TouchPedalView(Context context) {
        super(context);
        init();
    }

    public TouchPedalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TouchPedalView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setColor(DEFAULT_COLOR);
        fillPaint.setStyle(Paint.Style.FILL);

        borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(BORDER_COLOR);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(BORDER_WIDTH);

        indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        indicatorPaint.setColor(ACTIVE_COLOR);
        indicatorPaint.setStyle(Paint.Style.FILL);

        pedalRect = new RectF();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        pedalRect.set(BORDER_WIDTH, BORDER_WIDTH, w - BORDER_WIDTH, h - BORDER_WIDTH);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Draw the pedal background
        canvas.drawRoundRect(pedalRect, CORNER_RADIUS, CORNER_RADIUS, fillPaint);
        
        // Draw the indicator (active area)
        if (isTouching) {
            float indicatorHeight = pedalRect.height() * (1 - (currentValue + 1) / 2);
            RectF indicatorRect = new RectF(
                    pedalRect.left,
                    pedalRect.top + indicatorHeight,
                    pedalRect.right,
                    pedalRect.bottom
            );
            canvas.drawRoundRect(indicatorRect, CORNER_RADIUS, CORNER_RADIUS, indicatorPaint);
        }
        
        // Draw the border
        canvas.drawRoundRect(pedalRect, CORNER_RADIUS, CORNER_RADIUS, borderPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                isTouching = true;
                float y = event.getY();
                // Convert touch position to value (-1 to 1)
                // Top of pedal = -1 (reverse), Bottom of pedal = 1 (forward)
                currentValue = 1 - 2 * (y / getHeight());
                currentValue = Math.max(-1, Math.min(1, currentValue));
                
                if (valueChangeListener != null) {
                    valueChangeListener.onValueChanged(currentValue);
                }
                invalidate();
                return true;
                
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isTouching = false;
                currentValue = 0;
                if (valueChangeListener != null) {
                    valueChangeListener.onValueChanged(currentValue);
                    valueChangeListener.onTouchEnd();
                }
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    public void setOnValueChangeListener(OnValueChangeListener listener) {
        this.valueChangeListener = listener;
    }

    public float getCurrentValue() {
        return currentValue;
    }
}
