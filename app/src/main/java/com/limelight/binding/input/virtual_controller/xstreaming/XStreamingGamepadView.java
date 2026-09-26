package com.limelight.binding.input.virtual_controller.xstreaming;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The complete XStreaming on-screen gamepad, laid out as a single overlay and
 * driven entirely by a {@link XSButtonConfig} list (a profile), the same model
 * XStreaming's own editor and in-game renderer share
 * (features/controller-customization/lib/gamepadLayout.ts's buildDefaultLayout
 * and components/CustomVirtualGamepad.tsx).
 *
 * It emits input through a {@link Listener} and holds no reference to
 * Moonlight's streaming stack, so {@link XStreamingVirtualController} is what
 * turns its callbacks into an actual gamepad packet, and what turns a macro
 * slot's press into macro playback rather than a plain button flag.
 */
public class XStreamingGamepadView extends FrameLayout {

    public interface Listener {
        /** @param buttonName one of the XStreaming button/macro-slot names, e.g. "A", "Macro1". */
        void onButtonStateChanged(String buttonName, boolean pressed);

        /**
         * @param stickId "left" or "right"
         * @param x       horizontal deflection in [-1, 1]
         * @param y       vertical deflection in [-1, 1], positive is down
         */
        void onStickMoved(String stickId, float x, float y);

        /** Layout editing: a button (or stick placeholder) was dragged to a new spot and released. */
        void onElementMoved(String name, int xDp, int yDp);

        /** Layout editing: a button (or stick placeholder) was tapped to configure it. */
        void onElementTapped(String name);
    }

    public static final String STICK_LEFT = "left";
    public static final String STICK_RIGHT = "right";

    /** Alpha applied to a hidden element while in edit mode, so it's visible but marked as off. */
    private static final float HIDDEN_EDIT_ALPHA = 0.35f;

    // Stick geometry, in dp, matching XStreaming's CustomVirtualGamepad.tsx.
    private static final float FREE_LEFT_STICK_RADIUS_DP = 140f;
    private static final float FREE_LEFT_STICK_HANDLE_DP = 80f;
    private static final float FREE_RIGHT_STICK_RADIUS_DP = 150f;
    private static final float FREE_RIGHT_STICK_HANDLE_DP = 100f;
    // Fixed mode uses the same (left) radius for both sticks -- ported as-is.
    private static final float FIXED_STICK_RADIUS_DP = 140f;
    private static final float FIXED_STICK_HANDLE_DP = 80f;
    private static final int FIXED_STICK_BOX_DP = 120;

    private List<XSButtonConfig> layout = new ArrayList<>();
    /** 0 = fixed-position sticks, 1 = free (half-screen) sticks. */
    private int joystickMode = 1;
    private boolean editMode = false;
    private float controlOpacity = 0.7f;
    private boolean hapticsEnabled = false;
    private Listener listener;

    private final Map<String, XSButtonView> buttonViews = new LinkedHashMap<>();
    private XSAnalogStickView leftStick;
    private XSAnalogStickView rightStick;

    private final Paint gridPaint = new Paint();

    public XStreamingGamepadView(Context context) {
        this(context, null);
    }

    public XStreamingGamepadView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public XStreamingGamepadView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
        gridPaint.setColor(Color.argb(60, 255, 255, 255));
        gridPaint.setStrokeWidth(1f);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Replaces the whole layout (e.g. on profile switch) and rebuilds every view. */
    public void setLayout(List<XSButtonConfig> newLayout) {
        this.layout = newLayout;
        rebuild();
    }

    public List<XSButtonConfig> getLayout() {
        return layout;
    }

    public void setJoystickMode(int mode) {
        int next = mode == 0 ? 0 : 1;
        if (joystickMode == next) {
            return;
        }
        joystickMode = next;
        rebuild();
    }

    public int getJoystickMode() {
        return joystickMode;
    }

    /** Toggles layout editing: buttons become draggable/tappable and a snap grid is drawn. */
    public void setEditMode(boolean edit) {
        if (editMode == edit) {
            return;
        }
        editMode = edit;
        rebuild();
    }

    public boolean isEditMode() {
        return editMode;
    }

    /** Opacity of the whole pad, 0..1. Matches XStreaming's `virtual_gamepad_opacity`. */
    public void setControlOpacity(float opacity) {
        controlOpacity = Math.max(0f, Math.min(1f, opacity));
        applyOpacity();
    }

    public float getControlOpacity() {
        return controlOpacity;
    }

    public void setHapticsEnabled(boolean enabled) {
        hapticsEnabled = enabled;
        for (XSButtonView button : buttonViews.values()) {
            button.setHapticsEnabled(enabled);
        }
    }

    public XSButtonView findButtonView(String name) {
        return buttonViews.get(name);
    }

    private void rebuild() {
        removeAllViews();
        buttonViews.clear();
        leftStick = null;
        rightStick = null;

        for (XSButtonConfig cfg : layout) {
            if (!cfg.show && !editMode) {
                continue;
            }
            if (XSGamepadLayout.LEFT_STICK.equals(cfg.name)) {
                addStick(cfg, true);
            } else if (XSGamepadLayout.RIGHT_STICK.equals(cfg.name)) {
                addStick(cfg, false);
            } else {
                addButton(cfg);
            }
        }
        applyOpacity();
    }

    private void addButton(final XSButtonConfig cfg) {
        String drawableName = XSGamepadLayout.drawableNameFor(cfg.name);
        if (drawableName == null) {
            return;
        }
        XSButtonView button = new XSButtonView(getContext());
        button.setButtonName(drawableName);
        button.setHapticsEnabled(hapticsEnabled);
        button.setTag(cfg.name);
        button.setAlpha(cfg.show ? 1f : HIDDEN_EDIT_ALPHA);
        button.setEditable(editMode);

        if (editMode) {
            button.setOnDragListener(makeDragListener(cfg.name));
        } else {
            button.setOnButtonStateChangeListener((view, pressed) -> {
                if (listener != null) {
                    listener.onButtonStateChanged(cfg.name, pressed);
                }
            });
        }

        addView(button, layoutParamsFor(cfg));
        buttonViews.put(cfg.name, button);
    }

    /**
     * In edit mode, a fixed-position stick's box is represented by a plain
     * draggable/tappable placeholder (reusing the stick artwork) so it goes
     * through the same drag/snap/config plumbing as every other element. A
     * free-mode stick has no position to edit, so it is skipped while editing.
     */
    private void addStick(final XSButtonConfig cfg, boolean isLeft) {
        if (editMode) {
            if (joystickMode != 0) {
                return;
            }
            XSButtonView placeholder = new XSButtonView(getContext());
            placeholder.setDrawableIdle(androidx.core.content.ContextCompat.getDrawable(
                    getContext(), com.limelight.R.drawable.control_analog_stick_base));
            placeholder.setDrawablePressed(androidx.core.content.ContextCompat.getDrawable(
                    getContext(), com.limelight.R.drawable.control_analog_stick_handle));
            placeholder.setTag(cfg.name);
            placeholder.setAlpha(cfg.show ? 1f : HIDDEN_EDIT_ALPHA);
            placeholder.setEditable(true);
            placeholder.setOnDragListener(makeDragListener(cfg.name));

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    dp(FIXED_STICK_BOX_DP), dp(FIXED_STICK_BOX_DP), Gravity.TOP | Gravity.START);
            params.setMargins(dp(cfg.x), dp(cfg.y), 0, 0);
            addView(placeholder, params);
            buttonViews.put(cfg.name, placeholder);
            return;
        }

        XSAnalogStickView stick = new XSAnalogStickView(getContext());
        String stickId = isLeft ? STICK_LEFT : STICK_RIGHT;
        stick.setTag(cfg.name);
        stick.setStateChangedCallback(state -> {
            if (listener != null) {
                listener.onStickMoved(stickId, state.x, state.y);
            }
        });

        FrameLayout.LayoutParams params;
        if (joystickMode == 1) {
            stick.setRadius(dp(isLeft ? FREE_LEFT_STICK_RADIUS_DP : FREE_RIGHT_STICK_RADIUS_DP));
            stick.setHandleRadius(dp(isLeft ? FREE_LEFT_STICK_HANDLE_DP : FREE_RIGHT_STICK_HANDLE_DP));
            int halfWidth = getWidth() > 0 ? getWidth() / 2 : LayoutParams.MATCH_PARENT;
            params = new FrameLayout.LayoutParams(halfWidth, LayoutParams.MATCH_PARENT,
                    (isLeft ? Gravity.START : Gravity.END) | Gravity.TOP);
        } else {
            stick.setRadius(dp(FIXED_STICK_RADIUS_DP));
            stick.setHandleRadius(dp(FIXED_STICK_HANDLE_DP));
            params = new FrameLayout.LayoutParams(dp(FIXED_STICK_BOX_DP), dp(FIXED_STICK_BOX_DP),
                    Gravity.TOP | Gravity.START);
            params.setMargins(dp(cfg.x), dp(cfg.y), 0, 0);
        }

        addView(stick, isLeft ? 0 : Math.min(1, getChildCount()), params);
        if (isLeft) {
            leftStick = stick;
        } else {
            rightStick = stick;
        }
    }

    private XSButtonView.OnDragListener makeDragListener(final String name) {
        return new XSButtonView.OnDragListener() {
            @Override
            public void onDragMove(XSButtonView view, int leftPx, int topPx) {
                LayoutParams params = (LayoutParams) view.getLayoutParams();
                params.setMargins(leftPx, topPx, 0, 0);
                view.setLayoutParams(params);
            }

            @Override
            public void onDragEnd(XSButtonView view, int leftPx, int topPx) {
                int xDp = XSGamepadLayout.snapToGrid(pxToDp(leftPx));
                int yDp = XSGamepadLayout.snapToGrid(pxToDp(topPx));
                LayoutParams params = (LayoutParams) view.getLayoutParams();
                params.setMargins(dp(xDp), dp(yDp), 0, 0);
                view.setLayoutParams(params);
                if (listener != null) {
                    listener.onElementMoved(name, xDp, yDp);
                }
            }

            @Override
            public void onTap(XSButtonView view) {
                if (listener != null) {
                    listener.onElementTapped(name);
                }
            }
        };
    }

    private LayoutParams layoutParamsFor(XSButtonConfig cfg) {
        XSGamepadLayout.Size base = XSGamepadLayout.getButtonBaseSize(cfg.name);
        float scale = cfg.scale <= 0 ? 1f : cfg.scale;
        LayoutParams params = new LayoutParams(
                dp(base.width * scale), dp(base.height * scale), Gravity.TOP | Gravity.START);
        params.setMargins(dp(cfg.x), dp(cfg.y), 0, 0);
        return params;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Free-mode sticks are half-screen-wide; that width is only known once
        // the overlay itself has been measured.
        if (!editMode && joystickMode == 1 && w > 0) {
            resizeHalfScreenStick(leftStick, w / 2);
            resizeHalfScreenStick(rightStick, w / 2);
        }
    }

    private void resizeHalfScreenStick(XSAnalogStickView stick, int width) {
        if (stick == null) {
            return;
        }
        LayoutParams params = (LayoutParams) stick.getLayoutParams();
        if (params.width != width) {
            params.width = width;
            stick.setLayoutParams(params);
        }
    }

    private void applyOpacity() {
        for (Map.Entry<String, XSButtonView> entry : buttonViews.entrySet()) {
            boolean hiddenInEdit = editMode && isHidden(entry.getKey());
            entry.getValue().setAlpha(hiddenInEdit ? HIDDEN_EDIT_ALPHA : controlOpacity);
        }
        if (leftStick != null) {
            leftStick.setAlpha(controlOpacity);
        }
        if (rightStick != null) {
            rightStick.setAlpha(controlOpacity);
        }
    }

    private boolean isHidden(String name) {
        for (XSButtonConfig cfg : layout) {
            if (cfg.name.equals(name)) {
                return !cfg.show;
            }
        }
        return false;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (editMode) {
            drawGrid(canvas);
        }
        super.dispatchDraw(canvas);
    }

    private void drawGrid(Canvas canvas) {
        int gridPx = dp(XSGamepadLayout.LAYOUT_SNAP_GRID);
        if (gridPx <= 0) {
            return;
        }
        for (int x = 0; x < getWidth(); x += gridPx) {
            canvas.drawLine(x, 0, x, getHeight(), gridPaint);
        }
        for (int y = 0; y < getHeight(); y += gridPx) {
            canvas.drawLine(0, y, getWidth(), y, gridPaint);
        }
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, getResources().getDisplayMetrics()));
    }

    private float pxToDp(int px) {
        return px / getResources().getDisplayMetrics().density;
    }
}
