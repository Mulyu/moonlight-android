package com.limelight.binding.input.virtual_controller.xstreaming;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * One step of a macro's action sequence: either a set of buttons held together
 * for {@code durationMs} then released, or the named stick moved to (x, y) for
 * {@code durationMs} then recentred. Either way, {@code waitAfterMs} is an
 * extra pause (buttons released / stick centred) before the next step starts.
 *
 * Ported from XStreaming (features/controller-customization/lib/virtualMacro.ts's
 * VirtualMacroStep).
 */
public class XSMacroStep {
    public static final String TYPE_BUTTONS = "buttons";
    public static final String TYPE_STICK = "stick";

    public String type = TYPE_BUTTONS;
    public List<String> buttons = new ArrayList<>();
    /** "left" or "right"; only meaningful when type is {@link #TYPE_STICK}. */
    public String stick = "left";
    public float x = 0f;
    public float y = 0f;
    public int durationMs = 80;
    public int waitAfterMs = 0;

    public XSMacroStep() {
    }

    public static XSMacroStep buttons(int durationMs, String... buttonNames) {
        XSMacroStep step = new XSMacroStep();
        step.type = TYPE_BUTTONS;
        step.buttons = new ArrayList<>(new LinkedHashSet<>(java.util.Arrays.asList(buttonNames)));
        step.durationMs = durationMs;
        return step;
    }

    public static XSMacroStep stick(String stick, float x, float y, int durationMs) {
        XSMacroStep step = new XSMacroStep();
        step.type = TYPE_STICK;
        step.stick = "right".equals(stick) ? "right" : "left";
        step.x = clampAxis(x);
        step.y = clampAxis(y);
        step.durationMs = durationMs;
        return step;
    }

    public boolean isStick() {
        return TYPE_STICK.equals(type);
    }

    private static float clampAxis(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    public XSMacroStep copy() {
        XSMacroStep c = new XSMacroStep();
        c.type = type;
        c.buttons = new ArrayList<>(buttons);
        c.stick = stick;
        c.x = x;
        c.y = y;
        c.durationMs = durationMs;
        c.waitAfterMs = waitAfterMs;
        return c;
    }
}
