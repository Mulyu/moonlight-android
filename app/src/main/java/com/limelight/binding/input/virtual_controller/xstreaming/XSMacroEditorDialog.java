package com.limelight.binding.input.virtual_controller.xstreaming;

import android.app.AlertDialog;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.limelight.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Editor for a macro slot's ordered list of steps (a button combo held for a
 * duration, or a stick moved to a position for a duration), each followed by
 * an optional extra pause. Ported from XStreaming's per-macro step editor
 * (features/controller-customization), using plain Android widgets.
 *
 * Every change is pushed back through {@link Callback} immediately, matching
 * this port's auto-save-on-every-edit model used throughout the pad's
 * configuration UI.
 */
public final class XSMacroEditorDialog {

    public interface Callback {
        void onStepsChanged(List<XSMacroStep> steps);
    }

    private interface StepCallback {
        void onStep(XSMacroStep step);
    }

    private XSMacroEditorDialog() {
    }

    public static void show(Context context, List<XSMacroStep> initialSteps, Callback callback) {
        final List<XSMacroStep> steps = copyOf(initialSteps);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 12);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout stepsContainer = new LinearLayout(context);
        stepsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(stepsContainer);

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            stepsContainer.removeAllViews();
            for (int i = 0; i < steps.size(); i++) {
                stepsContainer.addView(buildStepRow(context, steps, i, callback, refresh));
            }
        };
        refresh[0].run();

        Button addButtonsStep = new Button(context);
        addButtonsStep.setText(R.string.xstreaming_macro_add_buttons_step);
        addButtonsStep.setOnClickListener(v -> showStepEditor(context, XSMacroStep.buttons(80), step -> {
            steps.add(step);
            callback.onStepsChanged(copyOf(steps));
            refresh[0].run();
        }));
        root.addView(addButtonsStep);

        Button addStickStep = new Button(context);
        addStickStep.setText(R.string.xstreaming_macro_add_stick_step);
        addStickStep.setOnClickListener(v -> showStepEditor(context, XSMacroStep.stick("left", 0f, 0f, 200), step -> {
            steps.add(step);
            callback.onStepsChanged(copyOf(steps));
            refresh[0].run();
        }));
        root.addView(addStickStep);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root);

        new AlertDialog.Builder(context)
                .setTitle(R.string.xstreaming_macro_editor_title)
                .setView(scroll)
                .setPositiveButton(R.string.xstreaming_config_done, null)
                .show();
    }

    private static LinearLayout buildStepRow(Context context, List<XSMacroStep> steps, int index,
                                              Callback callback, Runnable[] refresh) {
        XSMacroStep step = steps.get(index);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int vpad = dp(context, 4);
        row.setPadding(0, vpad, 0, vpad);

        TextView label = new TextView(context);
        label.setText((index + 1) + ". " + describeStep(step));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(labelParams);
        row.addView(label);

        Button up = new Button(context);
        up.setText("▲");
        up.setEnabled(index > 0);
        up.setOnClickListener(v -> {
            steps.add(index - 1, steps.remove(index));
            callback.onStepsChanged(copyOf(steps));
            refresh[0].run();
        });
        row.addView(up);

        Button down = new Button(context);
        down.setText("▼");
        down.setEnabled(index < steps.size() - 1);
        down.setOnClickListener(v -> {
            steps.add(index + 1, steps.remove(index));
            callback.onStepsChanged(copyOf(steps));
            refresh[0].run();
        });
        row.addView(down);

        Button edit = new Button(context);
        edit.setText(R.string.xstreaming_macro_step_edit);
        edit.setOnClickListener(v -> showStepEditor(context, step, edited -> {
            steps.set(index, edited);
            callback.onStepsChanged(copyOf(steps));
            refresh[0].run();
        }));
        row.addView(edit);

        Button delete = new Button(context);
        delete.setText("✕");
        delete.setOnClickListener(v -> {
            steps.remove(index);
            callback.onStepsChanged(copyOf(steps));
            refresh[0].run();
        });
        row.addView(delete);

        return row;
    }

    private static String describeStep(XSMacroStep step) {
        StringBuilder sb = new StringBuilder();
        if (step.isStick()) {
            sb.append("right".equals(step.stick) ? "R-Stick" : "L-Stick");
            sb.append(String.format(Locale.US, " (%.2f, %.2f)", step.x, step.y));
        } else {
            sb.append(step.buttons.isEmpty() ? "-" : TextUtils.join("+", step.buttons));
        }
        sb.append(" · ").append(step.durationMs).append("ms");
        if (step.waitAfterMs > 0) {
            sb.append(" +").append(step.waitAfterMs).append("ms");
        }
        return sb.toString();
    }

    private static void showStepEditor(Context context, XSMacroStep source, StepCallback onSave) {
        final XSMacroStep working = source.copy();
        boolean isStick = working.isStick();

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 12);
        root.setPadding(pad, pad, pad, pad);

        List<CheckBox> buttonChecks = new ArrayList<>();
        RadioGroup stickGroup = null;
        int rightRadioId = View.NO_ID;

        if (isStick) {
            TextView stickLabel = new TextView(context);
            stickLabel.setText(R.string.xstreaming_macro_step_stick_label);
            root.addView(stickLabel);

            stickGroup = new RadioGroup(context);
            stickGroup.setOrientation(RadioGroup.HORIZONTAL);
            RadioButton left = new RadioButton(context);
            left.setId(View.generateViewId());
            left.setText(R.string.xstreaming_macro_step_stick_left);
            RadioButton right = new RadioButton(context);
            rightRadioId = View.generateViewId();
            right.setId(rightRadioId);
            right.setText(R.string.xstreaming_macro_step_stick_right);
            stickGroup.addView(left);
            stickGroup.addView(right);
            stickGroup.check("right".equals(working.stick) ? rightRadioId : left.getId());
            root.addView(stickGroup);

            TextView xLabel = new TextView(context);
            root.addView(xLabel);
            SeekBar xSeek = new SeekBar(context);
            xSeek.setMax(200);
            xSeek.setProgress(Math.round((working.x + 1f) * 100));
            root.addView(xSeek);
            updateAxisLabel(xLabel, context, R.string.xstreaming_macro_step_x, working.x);
            xSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    working.x = progress / 100f - 1f;
                    updateAxisLabel(xLabel, context, R.string.xstreaming_macro_step_x, working.x);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });

            TextView yLabel = new TextView(context);
            root.addView(yLabel);
            SeekBar ySeek = new SeekBar(context);
            ySeek.setMax(200);
            ySeek.setProgress(Math.round((working.y + 1f) * 100));
            root.addView(ySeek);
            updateAxisLabel(yLabel, context, R.string.xstreaming_macro_step_y, working.y);
            ySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    working.y = progress / 100f - 1f;
                    updateAxisLabel(yLabel, context, R.string.xstreaming_macro_step_y, working.y);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        } else {
            TextView buttonsLabel = new TextView(context);
            buttonsLabel.setText(R.string.xstreaming_macro_step_buttons_label);
            root.addView(buttonsLabel);

            for (String name : XSGamepadLayout.MACRO_ALLOWED_BUTTONS) {
                CheckBox check = new CheckBox(context);
                check.setText(name);
                check.setChecked(working.buttons.contains(name));
                buttonChecks.add(check);
                root.addView(check);
            }
        }

        TextView durationLabel = new TextView(context);
        root.addView(durationLabel);
        SeekBar durationSeek = new SeekBar(context);
        durationSeek.setMax(497); // 30..5000ms in 10ms steps, matching XStreaming's step
        durationSeek.setProgress(Math.max(0, (working.durationMs - 30) / 10));
        root.addView(durationSeek);
        updateMsLabel(durationLabel, context, R.string.xstreaming_macro_step_duration, working.durationMs);
        durationSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                working.durationMs = 30 + progress * 10;
                updateMsLabel(durationLabel, context, R.string.xstreaming_macro_step_duration, working.durationMs);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        TextView waitLabel = new TextView(context);
        root.addView(waitLabel);
        SeekBar waitSeek = new SeekBar(context);
        waitSeek.setMax(300); // 0..3000ms in 10ms steps, matching XStreaming's step
        waitSeek.setProgress(working.waitAfterMs / 10);
        root.addView(waitSeek);
        updateMsLabel(waitLabel, context, R.string.xstreaming_macro_step_wait, working.waitAfterMs);
        waitSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                working.waitAfterMs = progress * 10;
                updateMsLabel(waitLabel, context, R.string.xstreaming_macro_step_wait, working.waitAfterMs);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root);

        final RadioGroup finalStickGroup = stickGroup;
        final int finalRightRadioId = rightRadioId;
        new AlertDialog.Builder(context)
                .setTitle(isStick ? R.string.xstreaming_macro_add_stick_step : R.string.xstreaming_macro_add_buttons_step)
                .setView(scroll)
                .setPositiveButton(R.string.xstreaming_macro_step_save, (dialog, which) -> {
                    if (!isStick) {
                        working.buttons.clear();
                        for (CheckBox check : buttonChecks) {
                            if (check.isChecked()) {
                                working.buttons.add(check.getText().toString());
                            }
                        }
                    } else if (finalStickGroup != null) {
                        working.stick = finalStickGroup.getCheckedRadioButtonId() == finalRightRadioId ? "right" : "left";
                    }
                    onSave.onStep(working);
                })
                .setNegativeButton(R.string.xstreaming_macro_step_cancel, null)
                .show();
    }

    private static void updateAxisLabel(TextView label, Context context, int stringRes, float value) {
        label.setText(context.getString(stringRes) + String.format(Locale.US, ": %.2f", value));
    }

    private static void updateMsLabel(TextView label, Context context, int stringRes, int value) {
        label.setText(context.getString(stringRes) + ": " + value + "ms");
    }

    private static List<XSMacroStep> copyOf(List<XSMacroStep> steps) {
        List<XSMacroStep> copy = new ArrayList<>();
        for (XSMacroStep s : steps) {
            copy.add(s.copy());
        }
        return copy;
    }

    private static int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }
}
