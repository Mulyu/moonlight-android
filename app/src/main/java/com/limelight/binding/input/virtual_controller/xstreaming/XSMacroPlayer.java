package com.limelight.binding.input.virtual_controller.xstreaming;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plays back a macro button's action sequence: each step either holds a set of
 * buttons for a duration then releases them, or moves a stick to a position for
 * a duration then recentres it, with an optional extra pause between steps. The
 * whole sequence can run once or loop with a fixed interval between passes.
 *
 * One instance drives one macro slot; {@link #stop()} is idempotent and always
 * leaves the target in the neutral (all released, sticks centred) state, so a
 * macro can never leave a button stuck down.
 */
public class XSMacroPlayer {

    /** What a macro step actually presses on the pad it's attached to. */
    public interface MacroTarget {
        void setMacroButtonPressed(String buttonName, boolean pressed);

        void setMacroStick(String stickId, float x, float y);
    }

    private final MacroTarget target;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private List<XSMacroStep> steps = new ArrayList<>();
    private boolean loop = false;
    private int loopIntervalMs = XSGamepadLayout.DEFAULT_MACRO_LOOP_INTERVAL_MS;
    private Runnable onStateChanged;

    // Bumped by every start()/stop() so stale posted callbacks from a previous
    // run recognise themselves as cancelled without needing to be individually
    // tracked and removed.
    private int generation = 0;
    private boolean playing = false;

    // Buttons currently held down by the active step, so stop() can release
    // exactly what is pressed regardless of where in the sequence it lands.
    private final Set<String> heldButtons = new HashSet<>();
    private String activeStick;

    public XSMacroPlayer(MacroTarget target) {
        this.target = target;
    }

    /** Fires whenever playing/looping starts or stops, so UI can reflect it. */
    public void setOnStateChangedListener(Runnable listener) {
        this.onStateChanged = listener;
    }

    public boolean isPlaying() {
        return playing;
    }

    public void start(List<XSMacroStep> steps, boolean loop, int loopIntervalMs) {
        stop();
        if (steps == null || steps.isEmpty()) {
            return;
        }
        this.steps = steps;
        this.loop = loop;
        this.loopIntervalMs = Math.max(0, loopIntervalMs);
        this.playing = true;
        generation++;
        notifyStateChanged();
        runStep(generation, 0);
    }

    public void stop() {
        generation++;
        boolean wasPlaying = playing;
        playing = false;
        releaseHeld();
        if (wasPlaying) {
            notifyStateChanged();
        }
    }

    private void releaseHeld() {
        for (String button : heldButtons) {
            target.setMacroButtonPressed(button, false);
        }
        heldButtons.clear();
        if (activeStick != null) {
            target.setMacroStick(activeStick, 0f, 0f);
            activeStick = null;
        }
    }

    private void runStep(final int myGeneration, final int index) {
        if (myGeneration != generation) {
            return;
        }
        if (index >= steps.size()) {
            if (loop) {
                handler.postDelayed(() -> runStep(myGeneration, 0), loopIntervalMs);
            } else {
                playing = false;
                notifyStateChanged();
            }
            return;
        }

        XSMacroStep step = steps.get(index);
        if (step.isStick()) {
            activeStick = step.stick;
            target.setMacroStick(step.stick, step.x, step.y);
        } else {
            for (String button : step.buttons) {
                if (XSGamepadLayout.MACRO_ALLOWED_BUTTONS.contains(button)) {
                    heldButtons.add(button);
                    target.setMacroButtonPressed(button, true);
                }
            }
        }

        handler.postDelayed(() -> {
            if (myGeneration != generation) {
                return;
            }
            if (step.isStick()) {
                target.setMacroStick(step.stick, 0f, 0f);
                activeStick = null;
            } else {
                for (String button : step.buttons) {
                    target.setMacroButtonPressed(button, false);
                    heldButtons.remove(button);
                }
            }
            handler.postDelayed(() -> runStep(myGeneration, index + 1), step.waitAfterMs);
        }, step.durationMs);
    }

    private void notifyStateChanged() {
        if (onStateChanged != null) {
            onStateChanged.run();
        }
    }
}
