package com.limelight.binding.input.virtual_controller.xstreaming;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.limelight.R;

/**
 * XStreaming-style analog stick.
 *
 * The stick has no fixed centre: it anchors wherever the finger first lands
 * inside the view and reports a displacement from that anchor, which is what
 * makes the "touch anywhere on this half of the screen" mode work. Releasing
 * the finger clears the anchor and re-centres the stick.
 *
 * Output is box-normalised (the same mapping XStreaming uses), so a fully
 * deflected diagonal reaches the corner of the square range rather than being
 * clamped to the unit circle.
 *
 * Ported from XStreaming (com.xstreaming.touchcontrols.AnalogStickView), with
 * the React Native event bridge replaced by a plain listener.
 */
public class XSAnalogStickView extends View {

    private float radius = 100f;
    private float handleRadius = 30f;
    private Drawable drawableBase;
    private Drawable drawableHandle;

    private XSVector state = new XSVector(0f, 0f);
    private StateChangedCallback stateChangedCallback;

    private final XSTouchTracker touchTracker = new XSTouchTracker();
    private XSVector center;
    private XSVector handlePosition = new XSVector(0f, 0f);

    public interface StateChangedCallback {
        /** @param state stick displacement, each axis in [-1, 1]. */
        void onStateChanged(XSVector state);
    }

    public XSAnalogStickView(Context context) {
        this(context, null);
    }

    public XSAnalogStickView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public XSAnalogStickView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, @Nullable AttributeSet attrs) {
        if (attrs != null) {
            TypedArray a = context.getTheme().obtainStyledAttributes(
                    attrs, R.styleable.XSAnalogStickView, 0, 0);
            try {
                radius = a.getDimension(R.styleable.XSAnalogStickView_xsRadius, radius);
                handleRadius = a.getDimension(R.styleable.XSAnalogStickView_xsHandleRadius, handleRadius);
                drawableBase = a.getDrawable(R.styleable.XSAnalogStickView_xsDrawableBase);
                drawableHandle = a.getDrawable(R.styleable.XSAnalogStickView_xsDrawableHandle);
            } finally {
                a.recycle();
            }
        }

        if (drawableBase == null || drawableHandle == null) {
            setDefaultDrawables();
        }

        touchTracker.setPositionChangedCallback(new XSTouchTracker.PositionChangedCallback() {
            @Override
            public void onPositionChanged(XSVector position) {
                updateState(position);
            }
        });
    }

    private void setDefaultDrawables() {
        try {
            if (drawableBase == null) {
                drawableBase = ContextCompat.getDrawable(getContext(), R.drawable.control_analog_stick_base);
            }
            if (drawableHandle == null) {
                drawableHandle = ContextCompat.getDrawable(getContext(), R.drawable.control_analog_stick_handle);
            }
        } catch (Exception e) {
            createFallbackDrawables();
        }

        if (drawableBase == null || drawableHandle == null) {
            createFallbackDrawables();
        }
    }

    private void createFallbackDrawables() {
        if (drawableBase == null) {
            GradientDrawable baseDrawable = new GradientDrawable();
            baseDrawable.setShape(GradientDrawable.OVAL);
            baseDrawable.setColor(0x80FFFFFF);
            baseDrawable.setStroke(2, 0xFFFFFFFF);
            drawableBase = baseDrawable;
        }

        if (drawableHandle == null) {
            GradientDrawable handleDrawable = new GradientDrawable();
            handleDrawable.setShape(GradientDrawable.OVAL);
            handleDrawable.setColor(0xFFFFFFFF);
            handleDrawable.setStroke(1, 0xFFCCCCCC);
            drawableHandle = handleDrawable;
        }
    }

    public void setRadius(float radius) {
        this.radius = radius;
        invalidate();
    }

    public float getRadius() {
        return radius;
    }

    public void setHandleRadius(float handleRadius) {
        this.handleRadius = handleRadius;
        invalidate();
    }

    public float getHandleRadius() {
        return handleRadius;
    }

    public void setStateChangedCallback(StateChangedCallback callback) {
        this.stateChangedCallback = callback;
    }

    public XSVector getState() {
        return state;
    }

    private void setState(XSVector value) {
        this.state = value;
        if (stateChangedCallback != null) {
            stateChangedCallback.onStateChanged(state);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Nothing is drawn until a finger anchors the stick; that is deliberate,
        // the free-placement modes should leave the video unobscured while idle.
        if (center == null) {
            return;
        }

        float circleRadius = radius + handleRadius;

        if (drawableBase != null) {
            drawableBase.setBounds(
                    (int) (center.x - circleRadius),
                    (int) (center.y - circleRadius),
                    (int) (center.x + circleRadius),
                    (int) (center.y + circleRadius));
            drawableBase.draw(canvas);
        }

        float handleX = center.x + handlePosition.x * radius;
        float handleY = center.y + handlePosition.y * radius;

        if (drawableHandle != null) {
            drawableHandle.setBounds(
                    (int) (handleX - handleRadius),
                    (int) (handleY - handleRadius),
                    (int) (handleX + handleRadius),
                    (int) (handleY + handleRadius));
            drawableHandle.draw(canvas);
        }
    }

    private void updateState(XSVector position) {
        if (radius <= 0f) {
            return;
        }

        if (position == null) {
            center = null;
            handlePosition = new XSVector(0f, 0f);
            setState(new XSVector(0f, 0f));
            invalidate();
            return;
        }

        if (center == null) {
            center = position;
        }

        XSVector dir = position.minus(center);
        float length = dir.getLength();
        if (length > 0) {
            float strength = length > radius ? 1.0f : length / radius;
            XSVector dirNormalized = dir.divide(length);
            handlePosition = dirNormalized.times(strength);

            // Box normalisation: scale the unit-circle direction out to the edge
            // of the unit square so the diagonals can reach full deflection.
            XSVector dirBoxNormalized;
            if (Math.abs(dirNormalized.x) > Math.abs(dirNormalized.y)) {
                dirBoxNormalized = dirNormalized.divide(Math.abs(dirNormalized.x));
            } else {
                dirBoxNormalized = dirNormalized.divide(Math.abs(dirNormalized.y));
            }
            setState(dirBoxNormalized.times(strength));
        } else {
            handlePosition = new XSVector(0f, 0f);
            setState(new XSVector(0f, 0f));
        }

        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        touchTracker.touchEvent(event);
        return true;
    }
}
