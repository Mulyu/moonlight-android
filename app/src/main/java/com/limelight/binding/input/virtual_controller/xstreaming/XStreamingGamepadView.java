package com.limelight.binding.input.virtual_controller.xstreaming;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;


import java.util.ArrayList;
import java.util.List;

/**
 * The complete XStreaming on-screen gamepad, laid out as a single overlay.
 *
 * This is a straight port of XStreaming's src/components/VirtualGamepad.tsx:
 * same button set, same drawables, same landscape positions (the React Native
 * style sheet works in dp, so its numbers carry over unchanged), and the same
 * two stick modes.
 *
 * It reports input through {@link Listener} and holds no reference to
 * Moonlight's streaming stack, so it can be dropped on any screen. Wiring it to
 * {@code ControllerHandler} is deliberately left out of this mock.
 */
public class XStreamingGamepadView extends FrameLayout {

    /** How the two analog sticks are presented. Mirrors XStreaming's `virtual_gamepad_joystick`. */
    public enum StickMode {
        /** Sticks sit at a fixed spot on the pad (XStreaming joystick mode 0). */
        FIXED,
        /** Each stick owns half the screen and anchors wherever the finger lands (mode 1). */
        FREE
    }

    public interface Listener {
        /**
         * @param buttonName one of the XStreaming button names, e.g. "A", "DPadUp", "LeftTrigger".
         */
        void onButtonStateChanged(String buttonName, boolean pressed);

        /**
         * @param stickId "left" or "right"
         * @param x       horizontal deflection in [-1, 1]
         * @param y       vertical deflection in [-1, 1], positive is down
         */
        void onStickMoved(String stickId, float x, float y);
    }

    public static final String STICK_LEFT = "left";
    public static final String STICK_RIGHT = "right";

    // Stick geometry, in raw pixels, matching the values VirtualGamepad.tsx passes
    // to the native view (React Native forwards these props unconverted).
    private static final float LEFT_STICK_RADIUS_PX = 140f;
    private static final float LEFT_STICK_HANDLE_RADIUS_PX = 80f;
    private static final float RIGHT_STICK_RADIUS_PX = 150f;
    private static final float RIGHT_STICK_HANDLE_RADIUS_PX = 100f;

    /** One button's identity, artwork and landscape placement (all distances in dp). */
    private static final class ButtonSpec {
        final String name;
        final String drawableName;
        final int widthDp;
        final int heightDp;
        final int gravity;
        final int leftDp;
        final int topDp;
        final int rightDp;
        final int bottomDp;

        ButtonSpec(String name, String drawableName, int widthDp, int heightDp,
                   int gravity, int leftDp, int topDp, int rightDp, int bottomDp) {
            this.name = name;
            this.drawableName = drawableName;
            this.widthDp = widthDp;
            this.heightDp = heightDp;
            this.gravity = gravity;
            this.leftDp = leftDp;
            this.topDp = topDp;
            this.rightDp = rightDp;
            this.bottomDp = bottomDp;
        }
    }

    private static final int TL = Gravity.TOP | Gravity.START;
    private static final int TR = Gravity.TOP | Gravity.END;
    private static final int BL = Gravity.BOTTOM | Gravity.START;
    private static final int BR = Gravity.BOTTOM | Gravity.END;
    private static final int BC = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;

    // Positions transcribed from the VirtualGamepad.tsx style sheet. The three
    // centre buttons are laid out there as `width * 0.5 + offset`; here they use
    // CENTER_HORIZONTAL, so the offset is shifted by half the 50dp button width.
    private static final ButtonSpec[] BUTTON_SPECS = new ButtonSpec[]{
            new ButtonSpec("LeftTrigger", "control_button_lt", 60, 60, TL, 30, 40, 0, 0),
            new ButtonSpec("RightTrigger", "control_button_rt", 60, 60, TR, 0, 30, 30, 0),
            new ButtonSpec("LeftShoulder", "control_button_lb", 50, 50, TL, 30, 110, 0, 0),
            new ButtonSpec("RightShoulder", "control_button_rb", 50, 50, TR, 0, 100, 30, 0),

            new ButtonSpec("A", "control_button_a", 60, 60, BR, 0, 0, 70, 25),
            new ButtonSpec("B", "control_button_b", 60, 60, BR, 0, 0, 25, 70),
            new ButtonSpec("X", "control_button_x", 60, 60, BR, 0, 0, 110, 70),
            new ButtonSpec("Y", "control_button_y", 60, 60, BR, 0, 0, 70, 115),

            new ButtonSpec("DPadLeft", "control_button_left", 70, 70, BL, 25, 0, 0, 70),
            new ButtonSpec("DPadUp", "control_button_up", 70, 70, BL, 75, 0, 0, 115),
            new ButtonSpec("DPadRight", "control_button_right", 70, 70, BL, 125, 0, 0, 70),
            new ButtonSpec("DPadDown", "control_button_down", 70, 70, BL, 75, 0, 0, 25),

            new ButtonSpec("View", "control_button_view", 50, 50, BC, 0, 0, 75, 5),
            new ButtonSpec("Nexus", "control_button_xbox", 50, 50, BC, 5, 0, 0, 5),
            new ButtonSpec("Menu", "control_button_menu", 50, 50, BC, 85, 0, 0, 5),
    };

    // L3/R3 move up out of the way when the sticks take over the whole screen,
    // exactly as the `joystick === 1` overrides in VirtualGamepad.tsx do.
    private static final ButtonSpec L3_FIXED =
            new ButtonSpec("LeftThumb", "control_button_left_joystick_down", 50, 50, BL, 225, 0, 0, 80);
    private static final ButtonSpec L3_FREE =
            new ButtonSpec("LeftThumb", "control_button_left_joystick_down", 50, 50, BL, 225, 0, 0, 30);
    private static final ButtonSpec R3_FIXED =
            new ButtonSpec("RightThumb", "control_button_right_joystick_down", 50, 50, BR, 0, 0, 235, 40);
    private static final ButtonSpec R3_FREE =
            new ButtonSpec("RightThumb", "control_button_right_joystick_down", 50, 50, BR, 0, 0, 225, 30);

    private final List<XSButtonView> buttons = new ArrayList<>();

    private XSAnalogStickView leftStick;
    private XSAnalogStickView rightStick;
    private XSButtonView l3Button;
    private XSButtonView r3Button;

    private Listener listener;
    private StickMode stickMode = StickMode.FIXED;
    private float controlOpacity = 0.7f;
    private boolean hapticsEnabled = false;

    public XStreamingGamepadView(Context context) {
        this(context, null);
    }

    public XStreamingGamepadView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public XStreamingGamepadView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        build();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public StickMode getStickMode() {
        return stickMode;
    }

    public void setStickMode(StickMode mode) {
        if (mode == null || mode == stickMode) {
            return;
        }
        stickMode = mode;
        rebuildSticks();
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
        for (XSButtonView button : buttons) {
            button.setHapticsEnabled(enabled);
        }
    }

    private void build() {
        // Only the controls themselves should swallow touches; gaps fall through
        // to whatever is underneath (the video surface, in a real integration).
        setClickable(false);
        setFocusable(false);

        for (ButtonSpec spec : BUTTON_SPECS) {
            addButton(spec);
        }

        rebuildSticks();
        applyOpacity();
    }

    private XSButtonView addButton(ButtonSpec spec) {
        XSButtonView button = new XSButtonView(getContext());
        button.setButtonName(spec.drawableName);
        button.setHapticsEnabled(hapticsEnabled);
        button.setTag(spec.name);
        button.setOnButtonStateChangeListener(new XSButtonView.OnButtonStateChangeListener() {
            @Override
            public void onButtonStateChanged(XSButtonView view, boolean pressed) {
                if (listener != null) {
                    listener.onButtonStateChanged(spec.name, pressed);
                }
            }
        });

        addView(button, layoutParamsFor(spec));
        buttons.add(button);
        return button;
    }

    private LayoutParams layoutParamsFor(ButtonSpec spec) {
        LayoutParams params = new LayoutParams(dp(spec.widthDp), dp(spec.heightDp), spec.gravity);
        params.setMargins(dp(spec.leftDp), dp(spec.topDp), dp(spec.rightDp), dp(spec.bottomDp));
        return params;
    }

    /**
     * Recreates the sticks (and repositions L3/R3) for the current {@link StickMode}.
     */
    private void rebuildSticks() {
        if (leftStick != null) {
            removeView(leftStick);
            leftStick = null;
        }
        if (rightStick != null) {
            removeView(rightStick);
            rightStick = null;
        }
        if (l3Button != null) {
            removeView(l3Button);
            buttons.remove(l3Button);
            l3Button = null;
        }
        if (r3Button != null) {
            removeView(r3Button);
            buttons.remove(r3Button);
            r3Button = null;
        }

        boolean free = stickMode == StickMode.FREE;

        leftStick = createStick(STICK_LEFT, LEFT_STICK_RADIUS_PX, LEFT_STICK_HANDLE_RADIUS_PX);
        rightStick = createStick(STICK_RIGHT, RIGHT_STICK_RADIUS_PX, RIGHT_STICK_HANDLE_RADIUS_PX);

        if (free) {
            // Each stick claims its half of the screen. They go in first so the
            // buttons drawn on top of them still receive their own touches.
            addView(leftStick, 0, halfScreenParams(Gravity.START));
            addView(rightStick, 1, halfScreenParams(Gravity.END));
        } else {
            addView(leftStick, 0, fixedStickParams(BL, 180, 150));
            addView(rightStick, 1, fixedStickParams(BR, 200, 100));
        }

        l3Button = addButton(free ? L3_FREE : L3_FIXED);
        r3Button = addButton(free ? R3_FREE : R3_FIXED);

        applyOpacity();
    }

    private XSAnalogStickView createStick(final String stickId, float radiusPx, float handleRadiusPx) {
        XSAnalogStickView stick = new XSAnalogStickView(getContext());
        stick.setRadius(radiusPx);
        stick.setHandleRadius(handleRadiusPx);
        stick.setTag(stickId);
        stick.setStateChangedCallback(new XSAnalogStickView.StateChangedCallback() {
            @Override
            public void onStateChanged(XSVector state) {
                if (listener != null) {
                    listener.onStickMoved(stickId, state.x, state.y);
                }
            }
        });
        return stick;
    }

    private LayoutParams halfScreenParams(int horizontalGravity) {
        int width = getWidth() > 0 ? getWidth() / 2 : LayoutParams.MATCH_PARENT;
        LayoutParams params = new LayoutParams(width, LayoutParams.MATCH_PARENT,
                horizontalGravity | Gravity.TOP);
        return params;
    }

    /** Fixed-mode sticks are 120dp windows onto the stick, per the RN style sheet. */
    private LayoutParams fixedStickParams(int gravity, int horizontalDp, int bottomDp) {
        LayoutParams params = new LayoutParams(dp(120), dp(120), gravity);
        if ((gravity & Gravity.END) == Gravity.END) {
            params.setMargins(0, 0, dp(horizontalDp), dp(bottomDp));
        } else {
            params.setMargins(dp(horizontalDp), 0, 0, dp(bottomDp));
        }
        return params;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        // Half-screen sticks can only be sized once the overlay knows how wide it is.
        if (stickMode == StickMode.FREE && w > 0) {
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
        for (XSButtonView button : buttons) {
            button.setAlpha(controlOpacity);
        }
        if (leftStick != null) {
            leftStick.setAlpha(controlOpacity);
        }
        if (rightStick != null) {
            rightStick.setAlpha(controlOpacity);
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, getResources().getDisplayMetrics());
    }
}
