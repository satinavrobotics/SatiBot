package com.satinavrobotics.satibot.depth.depth_sources;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.widget.NestedScrollView;

/**
 * A custom ScrollView that manages touch events for the sliding panel.
 * It allows scrolling within the panel while also supporting dragging the panel up and down.
 * When scrolling up within the panel, it prevents the panel from being hidden.
 * When at the top of the content and scrolling down, it allows the panel to be hidden.
 */
public class CustomScrollView extends NestedScrollView {
    private float startY;
    private float startX;
    private boolean isScrollingUp;
    private boolean isDragging = false;
    private final int touchSlop;
    private boolean isHandlingTouch = false;

    public CustomScrollView(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public CustomScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public CustomScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        final int action = ev.getAction();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                startY = ev.getY();
                startX = ev.getX();
                isDragging = false;
                isHandlingTouch = false;
                break;

            case MotionEvent.ACTION_MOVE:
                final float y = ev.getY();
                final float x = ev.getX();
                final float yDiff = Math.abs(y - startY);
                final float xDiff = Math.abs(x - startX);

                // Determine if we're scrolling vertically
                if (!isDragging && yDiff > touchSlop && yDiff > xDiff) {
                    isDragging = true;
                    isScrollingUp = y > startY;

                    // If we're scrolling up and not at the top, we should handle this touch
                    if (isScrollingUp && getScrollY() > 0) {
                        isHandlingTouch = true;
                        // Request parent to not intercept touch events
                        requestDisallowParentInterceptTouchEvent(true);
                        return true;
                    }
                }
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                isDragging = false;
                isHandlingTouch = false;
                requestDisallowParentInterceptTouchEvent(false);
                break;
        }

        // If we've decided to handle the touch, intercept it
        if (isHandlingTouch) {
            return true;
        }

        // Otherwise, let the parent decide
        return super.onInterceptTouchEvent(ev);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            startY = ev.getY();
        } else if (ev.getAction() == MotionEvent.ACTION_MOVE) {
            float currentY = ev.getY();
            isScrollingUp = currentY > startY;

            // If we're scrolling up and not at the top, we should handle this touch
            if (isScrollingUp && getScrollY() > 0) {
                isHandlingTouch = true;
                requestDisallowParentInterceptTouchEvent(true);
            } else if (!isScrollingUp && getScrollY() == 0) {
                // If we're at the top and scrolling down, let the parent handle it
                // This allows the panel to be hidden by dragging down
                isHandlingTouch = false;
                requestDisallowParentInterceptTouchEvent(false);
            }
        } else if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
            isHandlingTouch = false;
            requestDisallowParentInterceptTouchEvent(false);
        }

        return super.onTouchEvent(ev);
    }

    private void requestDisallowParentInterceptTouchEvent(boolean disallow) {
        CoordinatorLayout parent = (CoordinatorLayout) getParent().getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
        }
    }
}
