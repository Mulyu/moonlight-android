package com.limelight.binding.input.virtual_controller.xstreaming;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Short vibration pulse fired when a touch button goes down.
 *
 * Ported from XStreaming (com.xstreaming.touchcontrols.ButtonHaptics), with the
 * hardcoded "off" switch replaced by a runtime toggle so the host screen can
 * decide.
 */
public class XSHaptics {
    private final Context context;
    private boolean enabled;

    public XSHaptics(Context context) {
        this(context, false);
    }

    public XSHaptics(Context context, boolean enabled) {
        this.context = context;
        this.enabled = enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void trigger() {
        trigger(false);
    }

    public void trigger(boolean harder) {
        if (!enabled) {
            return;
        }

        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect effect;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int effectType = harder ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK;
                effect = VibrationEffect.createPredefined(effectType);
            } else {
                effect = VibrationEffect.createOneShot(10, harder ? 200 : 100);
            }
            vibrator.vibrate(effect);
        } else {
            vibrator.vibrate(10);
        }
    }
}
