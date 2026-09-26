package com.limelight.binding.input.virtual_controller.xstreaming;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.limelight.LimeLog;
import com.limelight.R;

/**
 * A single XStreaming-style touch button: an idle drawable that swaps to a
 * pressed drawable while a finger is down on it.
 *
 * Drawables are resolved by name ("control_button_a" plus "control_button_a_pressed")
 * so the whole pad can be built from the ported control_*.xml vector assets
 * without a drawable reference per button.
 *
 * Ported from XStreaming (com.xstreaming.touchcontrols.ButtonView).
 */
public class XSButtonView extends View {
    private static final String TAG = "XSButtonView";

    private final XSHaptics haptics;

    private boolean buttonPressed = false;
    private Drawable drawableIdle;
    private Drawable drawablePressed;
    private String buttonName = "";
    private OnButtonStateChangeListener stateChangeListener;

    public interface OnButtonStateChangeListener {
        void onButtonStateChanged(XSButtonView view, boolean pressed);
    }

    public XSButtonView(Context context) {
        this(context, null);
    }

    public XSButtonView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public XSButtonView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        haptics = new XSHaptics(context);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(
                    attrs, R.styleable.XSButtonView, defStyleAttr, 0);
            try {
                drawableIdle = a.getDrawable(R.styleable.XSButtonView_xsDrawableIdle);
                drawablePressed = a.getDrawable(R.styleable.XSButtonView_xsDrawablePressed);
                buttonName = a.getString(R.styleable.XSButtonView_xsButtonName);
            } finally {
                a.recycle();
            }
        }

        if (buttonName != null && !buttonName.isEmpty() &&
                (drawableIdle == null || drawablePressed == null)) {
            loadDrawablesByName(context.getResources(), buttonName);
        }

        setClickable(true);
    }

    public String getButtonName() {
        return buttonName;
    }

    public void setButtonName(String name) {
        this.buttonName = name;
        if (name != null && !name.isEmpty()) {
            loadDrawablesByName(getResources(), name);
            invalidate();
        }
    }

    private void loadDrawablesByName(Resources res, String baseName) {
        try {
            int idleResId = res.getIdentifier(baseName, "drawable", getContext().getPackageName());
            int pressedResId = res.getIdentifier(baseName + "_pressed", "drawable", getContext().getPackageName());

            if (idleResId != 0) {
                drawableIdle = ResourcesCompat.getDrawable(res, idleResId, null);
            }
            if (pressedResId != 0) {
                drawablePressed = ResourcesCompat.getDrawable(res, pressedResId, null);
            }
        } catch (Exception e) {
            LimeLog.warning(TAG + ": error loading drawables for button " + baseName + ": " + e);
        }
    }

    public void setDrawableIdle(Drawable drawable) {
        this.drawableIdle = drawable;
        invalidate();
    }

    public void setDrawablePressed(Drawable drawable) {
        this.drawablePressed = drawable;
        invalidate();
    }

    public void setHapticsEnabled(boolean enabled) {
        haptics.setEnabled(enabled);
    }

    public boolean isButtonPressed() {
        return buttonPressed;
    }

    public void setOnButtonStateChangeListener(OnButtonStateChangeListener listener) {
        this.stateChangeListener = listener;
    }

    private void setButtonPressed(boolean value) {
        if (buttonPressed == value) {
            return;
        }

        buttonPressed = value;
        if (value) {
            haptics.trigger();
        }
        invalidate();

        if (stateChangeListener != null) {
            stateChangeListener.onButtonStateChanged(this, buttonPressed);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Drawable drawable = buttonPressed ? drawablePressed : drawableIdle;
        if (drawable != null) {
            drawable.setBounds(
                    getPaddingLeft(),
                    getPaddingTop(),
                    getWidth() - getPaddingRight(),
                    getHeight() - getPaddingBottom());
            drawable.draw(canvas);
        }
    }

    /**
     * Returns the sibling button whose centre is closest to the touch point.
     *
     * Buttons on this pad overlap (the face-button cluster in particular). When a
     * press lands in an overlap, the topmost view receives it first; if a nearer
     * button exists we decline the event so it falls through to that one.
     */
    private View bestFittingTouchView(float x, float y) {
        int[] thisLocation = new int[2];
        getLocationOnScreen(thisLocation);

        float touchX = thisLocation[0] + x;
        float touchY = thisLocation[1] + y;

        if (!(getParent() instanceof ViewGroup)) {
            return this;
        }

        ViewGroup parent = (ViewGroup) getParent();

        View bestView = null;
        float bestDistance = Float.MAX_VALUE;

        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (!(child instanceof XSButtonView) || child.getVisibility() != VISIBLE) {
                continue;
            }

            float distance = calculateDistanceSquared(touchX, touchY, child);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestView = child;
            }
        }

        return bestView != null ? bestView : this;
    }

    private float calculateDistanceSquared(float touchX, float touchY, View view) {
        int[] viewLocation = new int[2];
        view.getLocationOnScreen(viewLocation);

        float centerX = viewLocation[0] + view.getWidth() / 2f;
        float centerY = viewLocation[1] + view.getHeight() / 2f;

        float dx = touchX - centerX;
        float dy = touchY - centerY;

        return dx * dx + dy * dy;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                View bestView = bestFittingTouchView(
                        event.getX(event.getActionIndex()),
                        event.getY(event.getActionIndex()));
                if (bestView != this) {
                    return false;
                }
                setButtonPressed(true);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                setButtonPressed(false);
                break;
        }
        return true;
    }
}
