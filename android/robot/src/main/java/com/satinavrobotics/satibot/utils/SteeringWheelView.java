package com.satinavrobotics.satibot.utils;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * A custom view that implements a steering wheel control.
 * The wheel can be rotated to control steering angle.
 */
public class SteeringWheelView extends View {
    private static final int WHEEL_COLOR = Color.parseColor("#3F51B5");
    private static final int WHEEL_BORDER_COLOR = Color.parseColor("#303F9F");
    private static final int WHEEL_CENTER_COLOR = Color.parseColor("#FF4081");
    private static final int WHEEL_SPOKE_COLOR = Color.parseColor("#000000");
    private static final float WHEEL_BORDER_WIDTH = 4f;
    private static final float MAX_ROTATION_ANGLE = 90f; // Maximum rotation angle in degrees

    private Paint wheelPaint;
    private Paint borderPaint;
    private Paint centerPaint;
    private Paint spokePaint;
    private RectF wheelRect;
    private float currentAngle = 0f; // Current rotation angle in degrees
    private float currentSteeringValue = 0f; // Range: -1 to 1
    private float lastTouchX;
    private float lastTouchY;
    private boolean isTouching = false;
    private OnSteeringValueChangeListener valueChangeListener;

    public interface OnSteeringValueChangeListener {
        void onSteeringValueChanged(float value);
        void onTouchEnd();
    }

    public SteeringWheelView(Context context) {
        super(context);
        init();
    }

    public SteeringWheelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SteeringWheelView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Initialize wheel paint
        wheelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        wheelPaint.setColor(WHEEL_COLOR);
        wheelPaint.setStyle(Paint.Style.FILL);

        // Initialize border paint
        borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(WHEEL_BORDER_COLOR);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(WHEEL_BORDER_WIDTH);

        // Initialize center paint
        centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerPaint.setColor(WHEEL_CENTER_COLOR);
        centerPaint.setStyle(Paint.Style.FILL);

        // Initialize spoke paint
        spokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        spokePaint.setColor(WHEEL_SPOKE_COLOR);
        spokePaint.setStyle(Paint.Style.STROKE);
        spokePaint.setStrokeWidth(WHEEL_BORDER_WIDTH);

        // Initialize wheel rectangle
        wheelRect = new RectF();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        
        // Make the wheel a circle that fits within the view
        int size = Math.min(w, h);
        int padding = (int) (size * 0.1f); // 10% padding
        
        wheelRect.left = padding;
        wheelRect.top = padding;
        wheelRect.right = size - padding;
        wheelRect.bottom = size - padding;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        // Save the canvas state
        canvas.save();
        
        // Rotate the canvas based on the current angle
        canvas.rotate(currentAngle, wheelRect.centerX(), wheelRect.centerY());
        
        // Draw the wheel
        canvas.drawOval(wheelRect, wheelPaint);
        
        // Draw the border
        canvas.drawOval(wheelRect, borderPaint);
        
        // Draw the center
        float centerRadius = wheelRect.width() * 0.15f;
        canvas.drawCircle(wheelRect.centerX(), wheelRect.centerY(), centerRadius, centerPaint);
        
        // Draw the spokes
        float innerRadius = centerRadius;
        float outerRadius = wheelRect.width() / 2 - WHEEL_BORDER_WIDTH;
        
        // Horizontal spoke
        canvas.drawLine(
                wheelRect.centerX() - outerRadius, 
                wheelRect.centerY(), 
                wheelRect.centerX() - innerRadius, 
                wheelRect.centerY(), 
                spokePaint);
        canvas.drawLine(
                wheelRect.centerX() + innerRadius, 
                wheelRect.centerY(), 
                wheelRect.centerX() + outerRadius, 
                wheelRect.centerY(), 
                spokePaint);
        
        // Vertical spoke
        canvas.drawLine(
                wheelRect.centerX(), 
                wheelRect.centerY() - outerRadius, 
                wheelRect.centerX(), 
                wheelRect.centerY() - innerRadius, 
                spokePaint);
        canvas.drawLine(
                wheelRect.centerX(), 
                wheelRect.centerY() + innerRadius, 
                wheelRect.centerX(), 
                wheelRect.centerY() + outerRadius, 
                spokePaint);
        
        // Restore the canvas state
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                isTouching = true;
                lastTouchX = x;
                lastTouchY = y;
                return true;
                
            case MotionEvent.ACTION_MOVE:
                if (isTouching) {
                    // Calculate the angle change based on touch movement
                    float deltaX = x - lastTouchX;
                    float deltaY = y - lastTouchY;
                    
                    // Calculate the angle change based on horizontal movement
                    // This makes the wheel rotate more when moving horizontally
                    float angleChange = deltaX * 0.5f;
                    
                    // Update the current angle
                    currentAngle += angleChange;
                    
                    // Limit the rotation angle
                    currentAngle = Math.max(-MAX_ROTATION_ANGLE, Math.min(MAX_ROTATION_ANGLE, currentAngle));
                    
                    // Convert angle to steering value (-1 to 1)
                    currentSteeringValue = currentAngle / MAX_ROTATION_ANGLE;
                    
                    // Notify the listener
                    if (valueChangeListener != null) {
                        valueChangeListener.onSteeringValueChanged(currentSteeringValue);
                    }
                    
                    // Update the last touch position
                    lastTouchX = x;
                    lastTouchY = y;
                    
                    // Redraw the wheel
                    invalidate();
                }
                return true;
                
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isTouching = false;
                
                // Auto-center the wheel
                currentAngle = 0;
                currentSteeringValue = 0;
                
                // Notify the listener
                if (valueChangeListener != null) {
                    valueChangeListener.onSteeringValueChanged(currentSteeringValue);
                    valueChangeListener.onTouchEnd();
                }
                
                // Redraw the wheel
                invalidate();
                return true;
        }
        
        return super.onTouchEvent(event);
    }

    public void setOnSteeringValueChangeListener(OnSteeringValueChangeListener listener) {
        this.valueChangeListener = listener;
    }

    public float getCurrentSteeringValue() {
        return currentSteeringValue;
    }
}
