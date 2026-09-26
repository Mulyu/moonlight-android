package com.limelight.binding.input.virtual_controller.xstreaming;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Layout constants and the canonical default layout for the XStreaming-style
 * pad: the same numbers XStreaming's editor and in-game renderer both build
 * from (features/controller-customization/lib/gamepadLayout.ts and
 * lib/virtualMacro.ts), so a freshly installed profile looks identical here and
 * there.
 */
public final class XSGamepadLayout {
    private XSGamepadLayout() {
    }

    // ---- Macro slots ----

    public static final String[] MACRO_BUTTON_NAMES = {"Macro1", "Macro2", "Macro3"};

    public static boolean isMacroButtonName(String name) {
        return "Macro1".equals(name) || "Macro2".equals(name) || "Macro3".equals(name);
    }

    /** Which stream-facing gamepad buttons a macro step is allowed to press. */
    public static final Set<String> MACRO_ALLOWED_BUTTONS = new LinkedHashSet<>(Arrays.asList(
            "A", "B", "X", "Y",
            "LeftShoulder", "RightShoulder", "LeftTrigger", "RightTrigger",
            "View", "Menu", "LeftThumb", "RightThumb",
            "DPadUp", "DPadDown", "DPadLeft", "DPadRight",
            "Nexus"
    ));

    public static final int DEFAULT_MACRO_LOOP_INTERVAL_MS = 500;

    /**
     * One default-positioned button per slot, stacked so they don't overlap.
     * Placed mid-left, clear of the default layout's other controls.
     */
    public static List<XSButtonConfig> createDefaultMacroButtons(int width, int height) {
        List<XSButtonConfig> list = new ArrayList<>();
        for (int i = 0; i < MACRO_BUTTON_NAMES.length; i++) {
            XSButtonConfig button = new XSButtonConfig(
                    MACRO_BUTTON_NAMES[i],
                    Math.round(width * 0.5f - 30),
                    Math.round(height - 130 - i * 70));
            list.add(button);
        }
        return list;
    }

    /** Ensures a saved layout carries all three macro slots (older saved layouts may predate them). */
    public static List<XSButtonConfig> ensureMacroButtons(List<XSButtonConfig> buttons, List<XSButtonConfig> fallback) {
        List<XSButtonConfig> next = new ArrayList<>(buttons);
        for (XSButtonConfig f : fallback) {
            boolean present = false;
            for (XSButtonConfig b : next) {
                if (f.name.equals(b.name)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                next.add(f.copy());
            }
        }
        return next;
    }

    // ---- Snapping ----

    /** Editor drag snapping, in dp: positions land on this grid instead of being pixel-exact. */
    public static final int LAYOUT_SNAP_GRID = 20;

    public static int snapToGrid(float value) {
        return Math.round(value / LAYOUT_SNAP_GRID) * LAYOUT_SNAP_GRID;
    }

    // ---- Base sizes ----

    public static final class Size {
        public final int width;
        public final int height;

        public Size(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    /** Canonical on-screen base size (before scale) for each button, in dp. */
    public static Size getButtonBaseSize(String name) {
        if ("A".equals(name) || "B".equals(name) || "X".equals(name) || "Y".equals(name)) {
            return new Size(60, 60);
        }
        if (name != null && name.contains("DPad")) {
            return new Size(70, 70);
        }
        if (isMacroButtonName(name)) {
            return new Size(60, 60);
        }
        return new Size(50, 50);
    }

    // ---- Default layout ----

    public static final String LEFT_STICK = "LeftStick";
    public static final String RIGHT_STICK = "RightStick";

    /** Maps a gamepad-button/macro-slot name to its idle drawable's base name. */
    public static String drawableNameFor(String buttonName) {
        switch (buttonName) {
            case "LeftTrigger": return "control_button_lt";
            case "RightTrigger": return "control_button_rt";
            case "LeftShoulder": return "control_button_lb";
            case "RightShoulder": return "control_button_rb";
            case "A": return "control_button_a";
            case "B": return "control_button_b";
            case "X": return "control_button_x";
            case "Y": return "control_button_y";
            case "DPadUp": return "control_button_up";
            case "DPadDown": return "control_button_down";
            case "DPadLeft": return "control_button_left";
            case "DPadRight": return "control_button_right";
            case "LeftThumb": return "control_button_left_joystick_down";
            case "RightThumb": return "control_button_right_joystick_down";
            case "View": return "control_button_view";
            case "Menu": return "control_button_menu";
            case "Nexus": return "control_button_xbox";
            case "Macro1": return "control_button_macro1";
            case "Macro2": return "control_button_macro2";
            case "Macro3": return "control_button_macro3";
            default: return null;
        }
    }

    /**
     * The single canonical default layout: identical button set and positions to
     * XStreaming's buildDefaultLayout(width, height), in the pad's own pixel
     * space (top-left anchored, dp).
     */
    public static List<XSButtonConfig> buildDefaultLayout(int width, int height) {
        int nexusLeft = Math.round(width * 0.5f - 20);
        int viewLeft = Math.round(width * 0.5f - 100);
        int menuLeft = Math.round(width * 0.5f + 60);

        List<XSButtonConfig> list = new ArrayList<>();
        list.add(new XSButtonConfig("LeftTrigger", 30, 40));
        list.add(new XSButtonConfig("RightTrigger", width - 30, 40));
        list.add(new XSButtonConfig("LeftShoulder", 30, 100));
        list.add(new XSButtonConfig("RightShoulder", width - 30, 110));
        list.add(new XSButtonConfig("A", width - 90, height - 60));
        list.add(new XSButtonConfig("B", width - 40, height - 110));
        list.add(new XSButtonConfig("X", width - 140, height - 110));
        list.add(new XSButtonConfig("Y", width - 90, height - 160));
        list.add(new XSButtonConfig("LeftThumb", 210, height - 80));
        list.add(new XSButtonConfig("RightThumb", width - 235, height - 70));
        list.add(new XSButtonConfig("View", viewLeft, height - 30));
        list.add(new XSButtonConfig("Nexus", nexusLeft, height - 50));
        list.add(new XSButtonConfig("Menu", menuLeft, height - 30));
        list.add(new XSButtonConfig("DPadUp", 85, height - 145));
        list.add(new XSButtonConfig("DPadLeft", 35, height - 95));
        list.add(new XSButtonConfig("DPadDown", 85, height - 45));
        list.add(new XSButtonConfig("DPadRight", 135, height - 95));
        list.add(new XSButtonConfig(LEFT_STICK, 175, height - 205));
        list.add(new XSButtonConfig(RIGHT_STICK, width - 265, height - 195));
        list.addAll(createDefaultMacroButtons(width, height));
        return list;
    }
}
