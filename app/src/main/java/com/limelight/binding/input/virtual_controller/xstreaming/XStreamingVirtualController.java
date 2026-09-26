package com.limelight.binding.input.virtual_controller.xstreaming;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import com.limelight.binding.input.ControllerHandler;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.preferences.PreferenceConfiguration;

/**
 * Drives the streaming session from the XStreaming-style touch pad.
 *
 * This is the piece that was deliberately left out of the original port: the
 * pad itself knows nothing about streaming, so this class keeps the aggregate
 * gamepad state and pushes it through {@link ControllerHandler#reportOscState},
 * exactly as Moonlight's own on-screen controller does. To the stream the pad
 * is then just another source of gamepad input.
 */
public class XStreamingVirtualController {

    /** Full-scale analog value; matches Moonlight's own on-screen controller. */
    private static final short STICK_SCALE = 0x7FFE;
    private static final byte TRIGGER_PRESSED = (byte) 0xFF;
    private static final byte TRIGGER_RELEASED = (byte) 0x00;

    private final ControllerHandler controllerHandler;
    private final FrameLayout parentLayout;
    private final Context context;
    private final XStreamingGamepadView gamepadView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Aggregate pad state, mutated in place and resent on every change.
    private int inputMap = 0;
    private byte leftTrigger = TRIGGER_RELEASED;
    private byte rightTrigger = TRIGGER_RELEASED;
    private short leftStickX, leftStickY, rightStickX, rightStickY;

    private final Runnable retransmitRunnable = new Runnable() {
        @Override
        public void run() {
            report();
        }
    };

    public XStreamingVirtualController(ControllerHandler controllerHandler,
                                       FrameLayout parentLayout,
                                       Context context) {
        this.controllerHandler = controllerHandler;
        this.parentLayout = parentLayout;
        this.context = context;

        gamepadView = new XStreamingGamepadView(context);
        gamepadView.setListener(new XStreamingGamepadView.Listener() {
            @Override
            public void onButtonStateChanged(String buttonName, boolean pressed) {
                handleButton(buttonName, pressed);
            }

            @Override
            public void onStickMoved(String stickId, float x, float y) {
                handleStick(stickId, x, y);
            }
        });

        applyPreferences();

        parentLayout.addView(gamepadView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /**
     * The pad follows the on-screen controller preferences the user has already
     * set, rather than introducing a second set of knobs for the same things.
     */
    private void applyPreferences() {
        PreferenceConfiguration prefConfig = PreferenceConfiguration.readPreferences(context);
        gamepadView.setControlOpacity(prefConfig.oscOpacity / 100f);
        gamepadView.setHapticsEnabled(prefConfig.vibrateOsc);
        gamepadView.setStickMode(prefConfig.enableNewAnalogStick
                ? XStreamingGamepadView.StickMode.FREE
                : XStreamingGamepadView.StickMode.FIXED);
    }

    private void handleButton(String buttonName, boolean pressed) {
        // Triggers are analog on the wire even though the pad drives them as
        // plain buttons, so they are not part of the button bitmap.
        if ("LeftTrigger".equals(buttonName)) {
            leftTrigger = pressed ? TRIGGER_PRESSED : TRIGGER_RELEASED;
            send();
            return;
        }
        if ("RightTrigger".equals(buttonName)) {
            rightTrigger = pressed ? TRIGGER_PRESSED : TRIGGER_RELEASED;
            send();
            return;
        }

        int flag = flagFor(buttonName);
        if (flag == 0) {
            return;
        }

        if (pressed) {
            inputMap |= flag;
        } else {
            inputMap &= ~flag;
        }
        send();
    }

    private int flagFor(String buttonName) {
        boolean flip = PreferenceConfiguration.readPreferences(context).flipFaceButtons;
        switch (buttonName) {
            case "A": return flip ? ControllerPacket.B_FLAG : ControllerPacket.A_FLAG;
            case "B": return flip ? ControllerPacket.A_FLAG : ControllerPacket.B_FLAG;
            case "X": return flip ? ControllerPacket.Y_FLAG : ControllerPacket.X_FLAG;
            case "Y": return flip ? ControllerPacket.X_FLAG : ControllerPacket.Y_FLAG;
            case "LeftShoulder": return ControllerPacket.LB_FLAG;
            case "RightShoulder": return ControllerPacket.RB_FLAG;
            case "LeftThumb": return ControllerPacket.LS_CLK_FLAG;
            case "RightThumb": return ControllerPacket.RS_CLK_FLAG;
            case "View": return ControllerPacket.BACK_FLAG;
            case "Menu": return ControllerPacket.PLAY_FLAG;
            case "Nexus": return ControllerPacket.SPECIAL_BUTTON_FLAG;
            case "DPadUp": return ControllerPacket.UP_FLAG;
            case "DPadDown": return ControllerPacket.DOWN_FLAG;
            case "DPadLeft": return ControllerPacket.LEFT_FLAG;
            case "DPadRight": return ControllerPacket.RIGHT_FLAG;
            default: return 0;
        }
    }

    private void handleStick(String stickId, float x, float y) {
        // The pad reports screen coordinates (y grows downward); the protocol
        // wants the gamepad convention, y positive up.
        short sx = (short) (x * STICK_SCALE);
        short sy = (short) (-y * STICK_SCALE);

        if (XStreamingGamepadView.STICK_LEFT.equals(stickId)) {
            leftStickX = sx;
            leftStickY = sy;
        } else {
            rightStickX = sx;
            rightStickY = sy;
        }
        send();
    }

    private void report() {
        controllerHandler.reportOscState(
                inputMap,
                leftStickX, leftStickY,
                rightStickX, rightStickY,
                leftTrigger, rightTrigger);
    }

    private void send() {
        // Cancel retransmissions of prior gamepad state.
        handler.removeCallbacks(retransmitRunnable);

        report();

        // Same workaround Moonlight's own on-screen controller uses: GFE
        // sometimes discards gamepad packets that arrive close together, and
        // losing an axis-zeroing packet leaves a stick stuck, so the state is
        // retransmitted a few times unless another input event arrives first.
        handler.postDelayed(retransmitRunnable, 25);
        handler.postDelayed(retransmitRunnable, 50);
        handler.postDelayed(retransmitRunnable, 75);
    }

    /** Releases everything and pushes the neutral state, so nothing sticks. */
    private void releaseAll() {
        inputMap = 0;
        leftTrigger = TRIGGER_RELEASED;
        rightTrigger = TRIGGER_RELEASED;
        leftStickX = leftStickY = rightStickX = rightStickY = 0;
        send();
    }

    public void show() {
        applyPreferences();
        gamepadView.setVisibility(View.VISIBLE);
    }

    public void hide() {
        // Hiding with a button still held would leave it held on the host.
        releaseAll();
        gamepadView.setVisibility(View.GONE);
    }

    public boolean isShown() {
        return gamepadView.getVisibility() == View.VISIBLE;
    }

    /** @return 1 if the pad is now shown, 0 if hidden (mirrors VirtualController). */
    public int switchShowHide() {
        if (isShown()) {
            hide();
            return 0;
        }
        show();
        return 1;
    }

    /** Re-reads preferences and re-lays out, e.g. after a screen size change. */
    public void refreshLayout() {
        applyPreferences();
        gamepadView.requestLayout();
    }

    public void removeFromLayout() {
        handler.removeCallbacks(retransmitRunnable);
        parentLayout.removeView(gamepadView);
    }
}
