package com.limelight;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.limelight.binding.input.virtual_controller.xstreaming.XSButtonConfig;
import com.limelight.binding.input.virtual_controller.xstreaming.XSGamepadLayout;
import com.limelight.binding.input.virtual_controller.xstreaming.XSProfileStore;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Pre-stream hub for the XStreaming-style virtual controller: manage layout profiles and the
 * global defaults (joystick mode, opacity, haptics) in one place, rather than profile management
 * living only inside an active stream (behind the pad's own gear button) while these defaults
 * live in the separate general Settings screen.
 *
 * Per-profile overrides (a profile's own joystick-mode override, its per-element settings) are
 * still only editable from within an active stream, via the pad's own gear-button menu / edit
 * toolbar -- unchanged by this screen. "Edit Layout" here only becomes available when a stream
 * is already running in the background ({@link Game#instance} non-null), since there is no
 * standalone renderer for the pad outside of one.
 */
public class ControllerSettingsActivity extends AppCompatActivity {

    // Must match PreferenceConfiguration's own (private) constants and preferences.xml's keys.
    private static final String OSC_OPACITY_PREF_STRING = "seekbar_osc_opacity";
    private static final String VIBRATE_OSC_PREF_STRING = "checkbox_vibrate_osc";
    private static final String ANALOG_STICK_NEW_PREF_STRING = "checkbox_enable_analog_stick_new";

    private XSProfileStore store;
    private String activeProfile = "";

    private LinearLayout profileListContainer;
    private TextView opacityValueLabel;
    private Button freeButton;
    private Button fixedButton;
    private boolean joystickModeFree;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.title_controller_settings);

        store = new XSProfileStore(this);
        activeProfile = store.getActiveProfile();

        setContentView(buildRootView());
    }

    private View buildRootView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF10131A);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(sectionLabel(getString(R.string.xstreaming_menu_profiles)));
        profileListContainer = new LinearLayout(this);
        profileListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(profileListContainer);
        rebuildProfileList();

        Button newProfileButton = new Button(this);
        newProfileButton.setAllCaps(false);
        newProfileButton.setText(R.string.xstreaming_profile_new);
        LinearLayout.LayoutParams newProfileParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        newProfileParams.topMargin = dp(4);
        newProfileButton.setLayoutParams(newProfileParams);
        newProfileButton.setOnClickListener(v -> promptNewProfileName());
        root.addView(newProfileButton);

        root.addView(sectionLabel(getString(R.string.controller_settings_section_global)));

        PreferenceConfiguration prefConfig = PreferenceConfiguration.readPreferences(this);
        joystickModeFree = prefConfig.enableNewAnalogStick;

        LinearLayout globalCard = new LinearLayout(this);
        globalCard.setOrientation(LinearLayout.VERTICAL);
        globalCard.setBackgroundColor(0xFF171B24);
        int cardPad = dp(16);
        globalCard.setPadding(cardPad, cardPad, cardPad, cardPad);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(8);
        globalCard.setLayoutParams(cardParams);

        globalCard.addView(labeledRow(R.string.controller_settings_joystick_mode,
                R.string.controller_settings_joystick_mode_summary));

        LinearLayout segmented = new LinearLayout(this);
        segmented.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams segmentedParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        segmentedParams.topMargin = dp(8);
        segmentedParams.bottomMargin = dp(16);
        segmented.setLayoutParams(segmentedParams);

        freeButton = new Button(this);
        freeButton.setAllCaps(false);
        freeButton.setText(R.string.xstreaming_stick_mode_free);
        LinearLayout.LayoutParams freeParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        freeButton.setLayoutParams(freeParams);
        freeButton.setOnClickListener(v -> setJoystickModeFree(true));
        segmented.addView(freeButton);

        fixedButton = new Button(this);
        fixedButton.setAllCaps(false);
        fixedButton.setText(R.string.xstreaming_stick_mode_fixed);
        LinearLayout.LayoutParams fixedParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        fixedParams.leftMargin = dp(8);
        fixedButton.setLayoutParams(fixedParams);
        fixedButton.setOnClickListener(v -> setJoystickModeFree(false));
        segmented.addView(fixedButton);

        globalCard.addView(segmented);
        updateJoystickModeButtons();

        opacityValueLabel = new TextView(this);
        opacityValueLabel.setTextColor(0xFFF4F6F8);
        opacityValueLabel.setTextSize(15);
        globalCard.addView(opacityValueLabel);

        SeekBar opacitySeek = new SeekBar(this);
        opacitySeek.setMax(100);
        opacitySeek.setProgress(prefConfig.oscOpacity);
        updateOpacityLabel(prefConfig.oscOpacity);
        opacitySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                updateOpacityLabel(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                sharedPreferences().edit().putInt(OSC_OPACITY_PREF_STRING, seekBar.getProgress()).apply();
            }
        });
        LinearLayout.LayoutParams opacitySeekParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        opacitySeekParams.bottomMargin = dp(16);
        opacitySeek.setLayoutParams(opacitySeekParams);
        globalCard.addView(opacitySeek);

        LinearLayout hapticsRow = new LinearLayout(this);
        hapticsRow.setOrientation(LinearLayout.HORIZONTAL);
        hapticsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout hapticsLabels = new LinearLayout(this);
        hapticsLabels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hapticsLabelsParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hapticsLabels.setLayoutParams(hapticsLabelsParams);
        hapticsLabels.addView(plainText(R.string.controller_settings_haptics, 15, 0xFFF4F6F8));
        hapticsLabels.addView(plainText(R.string.controller_settings_haptics_summary, 13, 0xFF93A5AE));
        hapticsRow.addView(hapticsLabels);
        Switch hapticsSwitch = new Switch(this);
        hapticsSwitch.setChecked(prefConfig.vibrateOsc);
        hapticsSwitch.setOnCheckedChangeListener((b, checked) ->
                sharedPreferences().edit().putBoolean(VIBRATE_OSC_PREF_STRING, checked).apply());
        hapticsRow.addView(hapticsSwitch);
        globalCard.addView(hapticsRow);

        root.addView(globalCard);

        Button editLayoutButton = new Button(this);
        editLayoutButton.setAllCaps(false);
        LinearLayout.LayoutParams editLayoutParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        editLayoutParams.topMargin = dp(20);
        editLayoutButton.setLayoutParams(editLayoutParams);
        if (Game.instance != null) {
            editLayoutButton.setText(R.string.controller_settings_edit_layout);
            editLayoutButton.setOnClickListener(v -> {
                Game.instance.openControllerLayoutEditor();
                finish();
            });
        } else {
            editLayoutButton.setText(R.string.controller_settings_edit_layout_hint);
            editLayoutButton.setEnabled(false);
        }
        root.addView(editLayoutButton);

        return scroll;
    }

    private void setJoystickModeFree(boolean free) {
        joystickModeFree = free;
        sharedPreferences().edit().putBoolean(ANALOG_STICK_NEW_PREF_STRING, free).apply();
        updateJoystickModeButtons();
    }

    private void updateJoystickModeButtons() {
        freeButton.setSelected(joystickModeFree);
        fixedButton.setSelected(!joystickModeFree);
        freeButton.setAlpha(joystickModeFree ? 1f : 0.6f);
        fixedButton.setAlpha(joystickModeFree ? 0.6f : 1f);
    }

    private void updateOpacityLabel(int progress) {
        if (opacityValueLabel != null) {
            opacityValueLabel.setText(getString(R.string.controller_settings_opacity) + ": " + progress + "%");
        }
    }

    private SharedPreferences sharedPreferences() {
        return PreferenceManager.getDefaultSharedPreferences(this);
    }

    // ---- Profiles ----

    private void rebuildProfileList() {
        profileListContainer.removeAllViews();

        List<String> names = new ArrayList<>();
        names.add(""); // Default, always first.
        names.addAll(store.getProfileNames());

        for (String name : names) {
            profileListContainer.addView(buildProfileRow(name));
        }
    }

    private View buildProfileRow(final String name) {
        boolean active = name.equals(activeProfile);
        String display = name.isEmpty() ? getString(R.string.xstreaming_profile_default) : name;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(active ? 0x145EEAD4 : 0x00000000);
        int rowPad = dp(10);
        row.setPadding(rowPad, rowPad, rowPad, rowPad);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(4);
        row.setLayoutParams(rowParams);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labels.setLayoutParams(labelsParams);
        labels.setOnClickListener(v -> selectProfile(name));
        TextView nameView = new TextView(this);
        nameView.setText(display);
        nameView.setTextColor(0xFFF4F6F8);
        nameView.setTextSize(15);
        labels.addView(nameView);
        if (active) {
            TextView activeLabel = new TextView(this);
            activeLabel.setText(R.string.controller_settings_active_label);
            activeLabel.setTextColor(0xFF5EEAD4);
            activeLabel.setTextSize(12);
            labels.addView(activeLabel);
        }
        row.addView(labels);

        if (!name.isEmpty()) {
            Button rename = new Button(this);
            rename.setAllCaps(false);
            rename.setText(R.string.xstreaming_profile_rename);
            rename.setOnClickListener(v -> promptRenameProfile(name));
            row.addView(rename);

            Button delete = new Button(this);
            delete.setAllCaps(false);
            delete.setText(R.string.xstreaming_profile_delete);
            delete.setOnClickListener(v -> confirmDeleteProfile(name));
            row.addView(delete);
        }

        return row;
    }

    private void selectProfile(String name) {
        if (name.equals(activeProfile)) {
            return;
        }
        activeProfile = name;
        store.setActiveProfile(name);
        rebuildProfileList();
    }

    private void promptNewProfileName() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle(R.string.xstreaming_profile_new)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || name.equals(activeProfile)) {
                        return;
                    }
                    DisplayMetricsSize size = currentSizeDp();
                    List<XSButtonConfig> defaults = XSGamepadLayout.buildDefaultLayout(size.wDp, size.hDp);
                    store.saveLayout(name, defaults);
                    activeProfile = name;
                    store.setActiveProfile(name);
                    rebuildProfileList();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void promptRenameProfile(final String oldName) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(oldName);
        new AlertDialog.Builder(this)
                .setTitle(R.string.xstreaming_profile_rename)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(oldName)) {
                        return;
                    }
                    store.renameProfile(oldName, newName);
                    if (oldName.equals(activeProfile)) {
                        activeProfile = newName;
                    }
                    rebuildProfileList();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDeleteProfile(final String name) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.xstreaming_profile_delete)
                .setMessage(getString(R.string.xstreaming_profile_delete_confirm, name))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    store.deleteProfile(name);
                    if (name.equals(activeProfile)) {
                        activeProfile = "";
                        store.setActiveProfile("");
                    }
                    rebuildProfileList();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- Small view-building helpers ----

    private TextView sectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text.toUpperCase());
        label.setTextColor(0xFF93A5AE);
        label.setTextSize(12);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(20);
        params.bottomMargin = dp(8);
        label.setLayoutParams(params);
        return label;
    }

    private LinearLayout labeledRow(int titleRes, int summaryRes) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.addView(plainText(titleRes, 15, 0xFFF4F6F8));
        block.addView(plainText(summaryRes, 13, 0xFF93A5AE));
        return block;
    }

    private TextView plainText(int stringRes, float sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(stringRes);
        view.setTextColor(color);
        view.setTextSize(sizeSp);
        return view;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private static final class DisplayMetricsSize {
        final int wDp;
        final int hDp;
        DisplayMetricsSize(int wDp, int hDp) {
            this.wDp = wDp;
            this.hDp = hDp;
        }
    }

    private DisplayMetricsSize currentSizeDp() {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        return new DisplayMetricsSize(
                Math.round(metrics.widthPixels / metrics.density),
                Math.round(metrics.heightPixels / metrics.density));
    }
}
