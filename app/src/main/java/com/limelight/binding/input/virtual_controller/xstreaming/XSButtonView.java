package com.limelight.binding.input.virtual_controller.xstreaming;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
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
    private final int touchSlop;

    private boolean buttonPressed = false;
    private boolean forcedPressed = false;
    private Drawable drawableIdle;
    private Drawable drawablePressed;
    private String buttonName = "";
    private OnButtonStateChangeListener stateChangeListener;

    // Layout-editing state (see setEditable).
    private boolean editable = false;
    private boolean dragging = false;
    private float dragStartRawX, dragStartRawY;
    private int dragStartLeftPx, dragStartTopPx;
    private OnDragListener dragListener;

    public interface OnButtonStateChangeListener {
        void onButtonStateChanged(XSButtonView view, boolean pressed);
    }

    /**
     * Reports layout edits while {@link #setEditable} is on. Positions are the
     * view's pixel margins within its parent (the pad overlay), matching how it
     * is actually laid out; the listener is responsible for converting to/from
     * the profile's dp coordinate space.
     */
    public interface OnDragListener {
        /** Called continuously while a finger drags the button; not yet snapped or persisted. */
        void onDragMove(XSButtonView view, int leftPx, int topPx);

        /** Called once the finger lifts after a drag (or a plain tap, with the position unchanged). */
        void onDragEnd(XSButtonView view, int leftPx, int topPx);

        /** Called instead of onDragEnd's usual meaning when the touch was a tap, not a drag. */
        void onTap(XSButtonView view);
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
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

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

    /**
     * Draws the pressed artwork regardless of touch state, without firing the
     * state-change listener or haptics. Used for a toggle-hold button latched
     * on, or a macro slot currently looping, so the pad shows what is actually
     * being sent to the stream.
     */
    public void setForcedPressed(boolean forced) {
        if (forcedPressed == forced) {
            return;
        }
        forcedPressed = forced;
        invalidate();
    }

    public void setOnButtonStateChangeListener(OnButtonStateChangeListener listener) {
        this.stateChangeListener = listener;
    }

    /**
     * Switches between normal play (press/release reported to the stream) and
     * layout editing (drag to reposition, tap to configure). Any in-progress
     * touch is abandoned across the switch.
     */
    public void setEditable(boolean editable) {
        if (this.editable == editable) {
            return;
        }
        this.editable = editable;
        dragging = false;
        setButtonPressed(false);
    }

    public boolean isEditable() {
        return editable;
    }

    public void setOnDragListener(OnDragListener listener) {
        this.dragListener = listener;
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
        boolean visuallyPressed = buttonPressed || forcedPressed || (editable && dragging);
        Drawable drawable = visuallyPressed ? drawablePressed : drawableIdle;
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
        if (editable) {
            return onEditTouchEvent(event);
        }

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

    private boolean onEditTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                View bestView = bestFittingTouchView(event.getX(), event.getY());
                if (bestView != this) {
                    return false;
                }
                dragging = false;
                dragStartRawX = event.getRawX();
                dragStartRawY = event.getRawY();
                dragStartLeftPx = getLeft();
                dragStartTopPx = getTop();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getRawX() - dragStartRawX;
                float dy = event.getRawY() - dragStartRawY;
                if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    dragging = true;
                    invalidate();
                }
                if (dragging && dragListener != null) {
                    dragListener.onDragMove(this,
                            dragStartLeftPx + Math.round(dx),
                            dragStartTopPx + Math.round(dy));
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean wasDragging = dragging;
                dragging = false;
                invalidate();
                if (dragListener != null) {
                    if (wasDragging) {
                        dragListener.onDragEnd(this, getLeft(), getTop());
                    } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        dragListener.onTap(this);
                    }
                }
                return true;
            }
        }
        return true;
    }
}
