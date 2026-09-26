package com.limelight.binding.input.virtual_controller.xstreaming;

import java.util.ArrayList;
import java.util.List;

/**
 * One button (or stick, or macro slot)'s saved placement and behaviour within a
 * touch-controller profile: top-left position, scale, visibility, and the
 * optional auto-fire / toggle-hold / macro behaviours a button can carry.
 *
 * Ported from XStreaming (features/controller-customization/lib/gamepadLayout.ts's
 * ButtonConfig), minus the GFN virtual-keyboard-key fields that don't apply to
 * a Moonlight/GameStream session.
 */
public class XSButtonConfig {
    public String name;

    /** Top-left position, in the pad's own pixel space (matches the view's width/height). */
    public int x;
    public int y;

    public float scale = 1f;
    public boolean show = true;

    /** Auto-fire: while held, the button rapidly presses/releases. */
    public boolean turbo = false;

    /** Toggle-hold: a press latches the button on until pressed again. */
    public boolean holdToggle = false;

    /** Macro slots only (name is one of {@link XSGamepadLayout#MACRO_BUTTON_NAMES}). */
    public List<XSMacroStep> macroSteps = new ArrayList<>();
    public boolean macroLoopEnabled = false;
    public int macroLoopIntervalMs = XSGamepadLayout.DEFAULT_MACRO_LOOP_INTERVAL_MS;

    public XSButtonConfig() {
    }

    public XSButtonConfig(String name, int x, int y) {
        this.name = name;
        this.x = x;
        this.y = y;
    }

    public XSButtonConfig copy() {
        XSButtonConfig c = new XSButtonConfig(name, x, y);
        c.scale = scale;
        c.show = show;
        c.turbo = turbo;
        c.holdToggle = holdToggle;
        c.macroLoopEnabled = macroLoopEnabled;
        c.macroLoopIntervalMs = macroLoopIntervalMs;
        List<XSMacroStep> steps = new ArrayList<>();
        for (XSMacroStep step : macroSteps) {
            steps.add(step.copy());
        }
        c.macroSteps = steps;
        return c;
    }
}
