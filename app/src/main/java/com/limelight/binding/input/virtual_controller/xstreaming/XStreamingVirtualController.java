package com.limelight.binding.input.virtual_controller.xstreaming;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drives the streaming session from the XStreaming-style touch pad, and owns
 * everything about it that isn't just "draw a button and report presses":
 * profiles, an in-session layout editor (drag to move, a slider to resize,
 * show/hide, turbo, toggle-hold), macro playback for the three macro slots,
 * and the foldable cover-screen controls.
 *
 * The pad itself ({@link XStreamingGamepadView}) and its buttons
 * ({@link XSButtonView}) know nothing about streaming, profiles or macros --
 * this class is what turns their callbacks into an actual gamepad packet (via
 * {@link ControllerHandler#reportOscState}, the same entry point Moonlight's
 * own on-screen controller uses) or into macro playback / a layout edit.
 */
public class XStreamingVirtualController implements XSMacroPlayer.MacroTarget {

    /** Full-scale analog value; matches Moonlight's own on-screen controller. */
    private static final short STICK_SCALE = 0x7FFE;
    private static final byte TRIGGER_PRESSED = (byte) 0xFF;
    private static final byte TRIGGER_RELEASED = (byte) 0x00;

    /** Auto-fire toggle interval for a turbo button, matching XStreaming's own. */
    private static final int TURBO_INTERVAL_MS = 60;

    private final ControllerHandler controllerHandler;
    private final FrameLayout parentLayout;
    private final Context context;
    private final Activity activity;
    /** Stable per-game key ("pcUuid:appId"), or null if unavailable -- used only for last-profile memory. */
    private final String gameKey;

    private final XStreamingGamepadView gamepadView;
    private final Button gearButton;
    /** Persistent toolbar shown only while {@link #editMode} is active; see {@link #buildEditToolbar()}. */
    private final LinearLayout editToolbar;
    /** Right-docked panel that replaces the old per-element AlertDialog; see {@link #showElementConfigPanel}. */
    private final ScrollView elementPanel;
    private final int panelWidthPx;
    private Button profileChipButton;
    private Button gridToggleButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final XSProfileStore store;

    private String activeProfile = "";
    private List<XSButtonConfig> layout = new ArrayList<>();
    private boolean editMode = false;

    // Per-button runtime state for turbo (auto-fire) and toggle-hold, keyed by
    // button name. A button is in at most one of these behaviours at a time.
    private final Set<String> turboRunning = new HashSet<>();
    private final Set<String> turboState = new HashSet<>();
    private final Set<String> holdLatched = new HashSet<>();

    // One independent macro player per slot, so two (or three) macro buttons
    // can run completely different sequences -- including two loops -- at once.
    private final java.util.Map<String, XSMacroPlayer> macroPlayers = new java.util.HashMap<>();

    private XSCoverDisplayController coverController;

    // Aggregate pad state, mutated in place and resent on every change.
    private int inputMap = 0;
    private byte leftTrigger = TRIGGER_RELEASED;
    private byte rightTrigger = TRIGGER_RELEASED;
    private short leftStickX, leftStickY, rightStickX, rightStickY;

    private final Runnable retransmitRunnable = this::report;

    public XStreamingVirtualController(ControllerHandler controllerHandler,
                                       FrameLayout parentLayout,
                                       Context context) {
        this(controllerHandler, parentLayout, context, null, null);
    }

    public XStreamingVirtualController(ControllerHandler controllerHandler,
                                       FrameLayout parentLayout,
                                       Context context,
                                       Activity activity,
                                       String gameKey) {
        this.controllerHandler = controllerHandler;
        this.parentLayout = parentLayout;
        this.context = context;
        this.activity = activity != null ? activity : (context instanceof Activity ? (Activity) context : null);
        this.gameKey = gameKey;
        this.store = new XSProfileStore(context);

        for (String name : XSGamepadLayout.MACRO_BUTTON_NAMES) {
            XSMacroPlayer player = new XSMacroPlayer(this);
            player.setOnStateChangedListener(() -> updateMacroVisual(name, player));
            macroPlayers.put(name, player);
        }

        gamepadView = new XStreamingGamepadView(context);
        gamepadView.setListener(new XStreamingGamepadView.Listener() {
            @Override
            public void onButtonStateChanged(String buttonName, boolean pressed) {
                onPadButton(buttonName, pressed);
            }

            @Override
            public void onStickMoved(String stickId, float x, float y) {
                handleStick(stickId, x, y);
            }

            @Override
            public void onElementMoved(String name, int xDp, int yDp) {
                XSButtonConfig cfg = findConfig(name);
                if (cfg != null) {
                    cfg.x = xDp;
                    cfg.y = yDp;
                    persistLayout();
                }
            }

            @Override
            public void onElementTapped(String name) {
                XSButtonConfig cfg = findConfig(name);
                if (cfg != null) {
                    showElementConfigPanel(cfg);
                }
            }
        });

        gearButton = new Button(context);
        gearButton.setAlpha(0.25f);
        gearButton.setFocusable(false);
        gearButton.setBackgroundResource(R.drawable.ic_settings);
        gearButton.setOnClickListener(v -> showMainMenu());

        // Created now (so profileChipButton etc. exist by the time loadLayoutWhenReady()
        // below resolves a profile and calls refreshToolbarProfileChip()), added to
        // parentLayout further down alongside gamepadView/gearButton.
        panelWidthPx = dp(300);
        editToolbar = buildEditToolbar();
        editToolbar.setVisibility(View.GONE);
        elementPanel = new ScrollView(context);
        elementPanel.setBackgroundColor(0xF2171B24);
        elementPanel.setVisibility(View.GONE);

        applyPreferences();

        activeProfile = resolveActiveProfile();
        loadLayoutWhenReady();

        parentLayout.addView(gamepadView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        DisplayMetrics screen = context.getResources().getDisplayMetrics();
        int buttonSize = (int) (screen.heightPixels * 0.06f);
        FrameLayout.LayoutParams gearParams = new FrameLayout.LayoutParams(buttonSize, buttonSize);
        gearParams.leftMargin = 15;
        gearParams.topMargin = 15;
        parentLayout.addView(gearButton, gearParams);

        FrameLayout.LayoutParams toolbarParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        toolbarParams.topMargin = dp(12);
        parentLayout.addView(editToolbar, toolbarParams);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                panelWidthPx, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.END);
        parentLayout.addView(elementPanel, panelParams);

        if (this.activity != null) {
            coverController = new XSCoverDisplayController(this.activity);
            coverController.setListener(new XSCoverDisplayController.Listener() {
                @Override
                public void onButtonStateChanged(String buttonName, boolean pressed) {
                    handleButton(buttonName, pressed);
                }

                @Override
                public void onAvailabilityChanged(boolean available) {
                    syncCoverPresentation();
                }
            });
            coverController.startWatching();
        }
    }

    // ---- Profile / layout loading ----

    private String resolveActiveProfile() {
        if (gameKey != null && !gameKey.isEmpty()) {
            String last = store.getLastProfileForGame(gameKey);
            if (last != null) {
                return last;
            }
        }
        return store.getActiveProfile();
    }

    /**
     * The default layout's numbers depend on the pad's own size, which is not
     * known synchronously the first time this constructs (the parent layout
     * may not have gone through a measure/layout pass yet). Retry once via a
     * post if so; the parent is already on-screen in every real case, so this
     * only actually loops on the very first frame at most.
     */
    private void loadLayoutWhenReady() {
        int wPx = parentLayout.getWidth();
        int hPx = parentLayout.getHeight();
        if (wPx <= 0 || hPx <= 0) {
            gamepadView.post(this::loadLayoutWhenReady);
            return;
        }
        applyLayoutForProfile(activeProfile, wPx, hPx);
    }

    private void applyLayoutForProfile(String profileName, int wPx, int hPx) {
        float density = context.getResources().getDisplayMetrics().density;
        int wDp = Math.round(wPx / density);
        int hDp = Math.round(hPx / density);

        List<XSButtonConfig> saved = store.getLayout(profileName);
        List<XSButtonConfig> defaultMacros = XSGamepadLayout.createDefaultMacroButtons(wDp, hDp);
        if (saved != null) {
            layout = XSGamepadLayout.ensureMacroButtons(saved, defaultMacros);
        } else {
            layout = XSGamepadLayout.buildDefaultLayout(wDp, hDp);
        }

        activeProfile = profileName;
        gamepadView.setLayout(layout);
        gamepadView.setJoystickMode(resolveJoystickMode());
        gamepadView.setEditMode(editMode);
        refreshToolbarProfileChip();

        store.setActiveProfile(activeProfile);
        if (gameKey != null && !gameKey.isEmpty()) {
            store.setLastProfileForGame(gameKey, activeProfile);
        }

        syncCoverPresentation();
    }

    private int resolveJoystickMode() {
        int override = store.getJoystickMode(activeProfile);
        if (override == 0 || override == 1) {
            return override;
        }
        // No per-profile override: fall back to Moonlight's own free-stick
        // preference, so this pad respects the same global toggle as before
        // profiles existed.
        return PreferenceConfiguration.readPreferences(context).enableNewAnalogStick ? 1 : 0;
    }

    private XSButtonConfig findConfig(String name) {
        for (XSButtonConfig cfg : layout) {
            if (cfg.name.equals(name)) {
                return cfg;
            }
        }
        return null;
    }

    private void persistLayout() {
        store.saveLayout(activeProfile, layout);
    }

    /**
     * The pad follows the on-screen controller preferences the user has already
     * set, rather than introducing a second set of knobs for the same things.
     */
    private void applyPreferences() {
        PreferenceConfiguration prefConfig = PreferenceConfiguration.readPreferences(context);
        gamepadView.setControlOpacity(prefConfig.oscOpacity / 100f);
        gamepadView.setHapticsEnabled(prefConfig.vibrateOsc);
        gamepadView.setJoystickMode(resolveJoystickMode());
    }

    // ---- Input from the pad ----

    /** Routes a raw button press from the pad to turbo / toggle-hold / macro / plain handling. */
    private void onPadButton(String buttonName, boolean pressed) {
        if (XSGamepadLayout.isMacroButtonName(buttonName)) {
            onMacroButton(buttonName, pressed);
            return;
        }

        XSButtonConfig cfg = findConfig(buttonName);
        if (cfg != null && cfg.turbo) {
            if (pressed) {
                startTurbo(buttonName);
            } else {
                stopTurbo(buttonName);
            }
            return;
        }

        if (cfg != null && cfg.holdToggle) {
            if (pressed) {
                boolean latchedOn = !holdLatched.remove(buttonName);
                if (latchedOn) {
                    holdLatched.add(buttonName);
                }
                handleButton(buttonName, latchedOn);
                setForcedPressed(buttonName, latchedOn);
            }
            return;
        }

        handleButton(buttonName, pressed);
    }

    private void startTurbo(String buttonName) {
        if (!turboRunning.add(buttonName)) {
            return;
        }
        turboState.add(buttonName);
        handleButton(buttonName, true);
        runTurboTick(buttonName);
    }

    private void runTurboTick(String buttonName) {
        if (!turboRunning.contains(buttonName)) {
            return;
        }
        handler.postDelayed(() -> {
            if (!turboRunning.contains(buttonName)) {
                return;
            }
            boolean on = !turboState.remove(buttonName);
            if (on) {
                turboState.add(buttonName);
            }
            handleButton(buttonName, on);
            runTurboTick(buttonName);
        }, TURBO_INTERVAL_MS);
    }

    private void stopTurbo(String buttonName) {
        if (turboRunning.remove(buttonName)) {
            turboState.remove(buttonName);
            handleButton(buttonName, false);
        }
    }

    /**
     * A macro slot's press semantics (see features/controller-customization's
     * virtualMacro.ts, as used by XStreaming's NativeStream.tsx):
     *  - turbo: back-to-back repeats of the sequence for as long as it's held;
     *    stops on release. Takes priority over macroLoopEnabled.
     *  - macroLoopEnabled (no turbo): a tap toggles looping on/off; release
     *    does nothing.
     *  - neither: a tap plays the sequence once; release does nothing.
     */
    private void onMacroButton(String buttonName, boolean pressed) {
        XSButtonConfig cfg = findConfig(buttonName);
        XSMacroPlayer player = macroPlayers.get(buttonName);
        if (cfg == null || player == null || cfg.macroSteps.isEmpty()) {
            return;
        }

        if (cfg.turbo) {
            if (pressed) {
                player.start(cfg.macroSteps, true, 0);
            } else {
                player.stop();
            }
            return;
        }

        if (!pressed) {
            return;
        }

        if (cfg.macroLoopEnabled) {
            if (player.isPlaying()) {
                player.stop();
            } else {
                player.start(cfg.macroSteps, true, cfg.macroLoopIntervalMs);
            }
            return;
        }

        player.start(cfg.macroSteps, false, 0);
    }

    private void updateMacroVisual(String name, XSMacroPlayer player) {
        setForcedPressed(name, player.isPlaying());
    }

    private void setForcedPressed(String name, boolean forced) {
        XSButtonView view = gamepadView.findButtonView(name);
        if (view != null) {
            view.setForcedPressed(forced);
        }
    }

    // ---- XSMacroPlayer.MacroTarget: macros drive the same pipeline a direct press would ----

    @Override
    public void setMacroButtonPressed(String buttonName, boolean pressed) {
        handleButton(buttonName, pressed);
    }

    @Override
    public void setMacroStick(String stickId, float x, float y) {
        handleStick(stickId, x, y);
    }

    // ---- The actual gamepad packet ----

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

        for (String name : new ArrayList<>(turboRunning)) {
            stopTurbo(name);
        }
        holdLatched.clear();
        for (XSMacroPlayer player : macroPlayers.values()) {
            player.stop();
        }
    }

    // ---- Visibility / lifecycle ----

    public void show() {
        applyPreferences();
        gamepadView.setVisibility(View.VISIBLE);
        gearButton.setVisibility(View.VISIBLE);
        syncCoverPresentation();
    }

    public void hide() {
        // Hiding with a button still held would leave it held on the host.
        releaseAll();
        gamepadView.setVisibility(View.GONE);
        gearButton.setVisibility(View.GONE);
        syncCoverPresentation();
    }

    public boolean isShown() {
        return gamepadView.getVisibility() == View.VISIBLE;
    }

    /** True while the layout editor (drag/resize) is active; callers should ignore stray touches outside the pad. */
    public boolean isEditMode() {
        return editMode;
    }

    /** Enters layout-editing mode, e.g. from the in-game quick menu's "Controller Layout" entry. */
    public void enterEditMode() {
        setEditMode(true);
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
        for (XSMacroPlayer player : macroPlayers.values()) {
            player.stop();
        }
        if (coverController != null) {
            coverController.destroy();
            coverController = null;
        }
        parentLayout.removeView(gamepadView);
        parentLayout.removeView(gearButton);
        parentLayout.removeView(editToolbar);
        parentLayout.removeView(elementPanel);
    }

    // ---- Cover-screen presentation ----

    private void syncCoverPresentation() {
        if (coverController == null) {
            return;
        }
        boolean wantCover = isShown() && !editMode && store.getCoverEnabled(activeProfile);
        coverController.setLayout(store.getCoverLayout(activeProfile));
        if (wantCover && coverController.isAvailable()) {
            coverController.present();
        } else {
            coverController.dismiss();
        }
    }

    // ==================================================================
    // Layout editing UI. Every change here saves immediately: there is no
    // separate "unsaved changes" state to track or discard, unlike
    // XStreaming's own editor screen -- this pad only exists inside a live
    // stream, so there is no other moment a discard-on-exit prompt would ever
    // actually be reached.
    // ==================================================================

    private void setEditMode(boolean edit) {
        if (editMode == edit) {
            return;
        }
        editMode = edit;
        gamepadView.setEditMode(edit);
        gearButton.setAlpha(edit ? 0.9f : 0.25f);
        editToolbar.setVisibility(edit ? View.VISIBLE : View.GONE);
        if (edit) {
            refreshToolbarProfileChip();
            updateGridButtonText();
        } else {
            hideElementConfigPanel();
        }
        syncCoverPresentation();
    }

    private void showMainMenu() {
        String[] items = {
                editMode ? context.getString(R.string.xstreaming_menu_exit_editing)
                        : context.getString(R.string.xstreaming_menu_edit_layout),
                context.getString(R.string.xstreaming_menu_profiles),
                context.getString(R.string.xstreaming_menu_reset_layout),
                context.getString(R.string.xstreaming_menu_cover_controls),
        };
        new AlertDialog.Builder(context)
                .setTitle(R.string.xstreaming_menu_title)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            setEditMode(!editMode);
                            break;
                        case 1:
                            showProfilesDialog();
                            break;
                        case 2:
                            confirmResetLayout();
                            break;
                        case 3:
                            showCoverControlsDialog();
                            break;
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmResetLayout() {
        new AlertDialog.Builder(context)
                .setTitle(R.string.xstreaming_reset_title)
                .setMessage(R.string.xstreaming_reset_message)
                .setPositiveButton(android.R.string.ok, (d, w) -> resetLayoutToDefault())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void resetLayoutToDefault() {
        int wPx = parentLayout.getWidth();
        int hPx = parentLayout.getHeight();
        if (wPx <= 0 || hPx <= 0) {
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        int wDp = Math.round(wPx / density);
        int hDp = Math.round(hPx / density);
        layout = XSGamepadLayout.buildDefaultLayout(wDp, hDp);
        persistLayout();
        gamepadView.setLayout(layout);
        gamepadView.setEditMode(editMode);
    }

    // ---- Profiles ----

    private void showProfilesDialog() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        List<String> names = new ArrayList<>();
        names.add(""); // Default, always first.
        names.addAll(store.getProfileNames());

        AlertDialog[] dialogHolder = new AlertDialog[1];

        for (String name : names) {
            root.addView(buildProfileRow(name, dialogHolder));
        }

        Button addButton = new Button(context);
        addButton.setText(R.string.xstreaming_profile_new);
        addButton.setOnClickListener(v -> {
            if (dialogHolder[0] != null) {
                dialogHolder[0].dismiss();
            }
            promptNewProfileName();
        });
        root.addView(addButton);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.xstreaming_menu_profiles)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialogHolder[0] = dialog;
        dialog.show();
    }

    private View buildProfileRow(final String name, final AlertDialog[] dialogHolder) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView label = new TextView(context);
        String display = name.isEmpty() ? context.getString(R.string.xstreaming_profile_default) : name;
        label.setText(name.equals(activeProfile) ? "✓ " + display : display);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(labelParams);
        label.setOnClickListener(v -> {
            if (dialogHolder[0] != null) {
                dialogHolder[0].dismiss();
            }
            switchToProfile(name);
        });
        row.addView(label);

        if (!name.isEmpty()) {
            Button rename = new Button(context);
            rename.setText(R.string.xstreaming_profile_rename);
            rename.setOnClickListener(v -> {
                if (dialogHolder[0] != null) {
                    dialogHolder[0].dismiss();
                }
                promptRenameProfile(name);
            });
            row.addView(rename);

            Button delete = new Button(context);
            delete.setText(R.string.xstreaming_profile_delete);
            delete.setOnClickListener(v -> {
                if (dialogHolder[0] != null) {
                    dialogHolder[0].dismiss();
                }
                confirmDeleteProfile(name);
            });
            row.addView(delete);
        }

        return row;
    }

    private void switchToProfile(String name) {
        if (name.equals(activeProfile)) {
            return;
        }
        int wPx = parentLayout.getWidth();
        int hPx = parentLayout.getHeight();
        if (wPx <= 0 || hPx <= 0) {
            return;
        }
        applyLayoutForProfile(name, wPx, hPx);
    }

    private void promptNewProfileName() {
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(context)
                .setTitle(R.string.xstreaming_profile_new)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || name.equals(activeProfile)) {
                        return;
                    }
                    // Clone the layout the user is looking at right now, so a new
                    // profile starts as a copy rather than jumping to defaults.
                    List<XSButtonConfig> copy = new ArrayList<>();
                    for (XSButtonConfig cfg : layout) {
                        copy.add(cfg.copy());
                    }
                    store.saveLayout(name, copy);
                    int wPx = parentLayout.getWidth();
                    int hPx = parentLayout.getHeight();
                    if (wPx > 0 && hPx > 0) {
                        applyLayoutForProfile(name, wPx, hPx);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void promptRenameProfile(final String oldName) {
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(oldName);
        new AlertDialog.Builder(context)
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
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDeleteProfile(final String name) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.xstreaming_profile_delete)
                .setMessage(context.getString(R.string.xstreaming_profile_delete_confirm, name))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    store.deleteProfile(name);
                    if (name.equals(activeProfile)) {
                        int wPx = parentLayout.getWidth();
                        int hPx = parentLayout.getHeight();
                        if (wPx > 0 && hPx > 0) {
                            applyLayoutForProfile("", wPx, hPx);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- Cover-screen controls ----

    private void showCoverControlsDialog() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        Switch enableSwitch = new Switch(context);
        enableSwitch.setText(R.string.xstreaming_cover_enable);
        enableSwitch.setChecked(store.getCoverEnabled(activeProfile));
        enableSwitch.setOnCheckedChangeListener((b, checked) -> {
            store.setCoverEnabled(activeProfile, checked);
            syncCoverPresentation();
        });
        root.addView(enableSwitch);

        if (coverController != null && !coverController.isAvailable()) {
            TextView hint = new TextView(context);
            hint.setText(R.string.xstreaming_cover_unavailable_hint);
            hint.setPadding(0, dp(8), 0, dp(8));
            root.addView(hint);
        }

        final List<XSCoverButton> coverLayout = store.getCoverLayout(activeProfile);
        for (final XSCoverButton cb : coverLayout) {
            root.addView(buildCoverButtonRow(cb, coverLayout));
        }

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root);

        new AlertDialog.Builder(context)
                .setTitle(R.string.xstreaming_menu_cover_controls)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private View buildCoverButtonRow(final XSCoverButton cb, final List<XSCoverButton> coverLayout) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        CheckBox showCheck = new CheckBox(context);
        showCheck.setText(cb.label);
        showCheck.setChecked(cb.show);
        showCheck.setOnCheckedChangeListener((b, checked) -> {
            cb.show = checked;
            store.saveCoverLayout(activeProfile, coverLayout);
            if (coverController != null) {
                coverController.setLayout(coverLayout);
            }
        });
        row.addView(showCheck);

        final TextView sizeLabel = new TextView(context);
        updateCoverSizeLabel(sizeLabel, cb.size);
        row.addView(sizeLabel);

        SeekBar sizeSeek = new SeekBar(context);
        sizeSeek.setMax(35);
        sizeSeek.setProgress(Math.max(8, Math.round(cb.size * 100)));
        sizeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                cb.size = Math.max(0.08f, progress / 100f);
                updateCoverSizeLabel(sizeLabel, cb.size);
                store.saveCoverLayout(activeProfile, coverLayout);
                if (coverController != null) {
                    coverController.setLayout(coverLayout);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        row.addView(sizeSeek);

        return row;
    }

    // ---- Persistent edit-mode toolbar ----

    /**
     * Built once and shown for as long as {@link #editMode} is active, replacing the need
     * to reopen the gear-button menu for profiles/reset/cover controls while editing.
     * Text-label buttons (not icons) to match this class's existing dialog style and avoid
     * depending on new vector drawables that can't be visually checked in this environment.
     */
    private LinearLayout buildEditToolbar() {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xEB141820);
        int padH = dp(10), padV = dp(6);
        bar.setPadding(padH, padV, padH, padV);

        profileChipButton = new Button(context);
        styleToolbarButton(profileChipButton);
        profileChipButton.setOnClickListener(v -> showProfilesDialog());
        bar.addView(profileChipButton);

        gridToggleButton = new Button(context);
        styleToolbarButton(gridToggleButton);
        gridToggleButton.setOnClickListener(v -> toggleGrid());
        bar.addView(gridToggleButton);

        Button sticksButton = new Button(context);
        styleToolbarButton(sticksButton);
        sticksButton.setText(R.string.xstreaming_toolbar_sticks);
        sticksButton.setOnClickListener(v -> showStickSettingsDialog());
        bar.addView(sticksButton);

        Button coverButton = new Button(context);
        styleToolbarButton(coverButton);
        coverButton.setText(R.string.xstreaming_toolbar_cover);
        coverButton.setOnClickListener(v -> showCoverControlsDialog());
        bar.addView(coverButton);

        Button resetButton = new Button(context);
        styleToolbarButton(resetButton);
        resetButton.setText(R.string.xstreaming_toolbar_reset);
        resetButton.setOnClickListener(v -> confirmResetLayout());
        bar.addView(resetButton);

        Button doneButton = new Button(context);
        styleToolbarButton(doneButton);
        doneButton.setText(R.string.xstreaming_toolbar_done);
        doneButton.setOnClickListener(v -> setEditMode(false));
        bar.addView(doneButton);

        return bar;
    }

    private void styleToolbarButton(Button button) {
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        button.setLayoutParams(params);
    }

    private void refreshToolbarProfileChip() {
        if (profileChipButton == null) {
            return;
        }
        profileChipButton.setText(activeProfile.isEmpty()
                ? context.getString(R.string.xstreaming_profile_default)
                : activeProfile);
    }

    private void toggleGrid() {
        gamepadView.setGridVisible(!gamepadView.isGridVisible());
        updateGridButtonText();
    }

    private void updateGridButtonText() {
        if (gridToggleButton == null) {
            return;
        }
        gridToggleButton.setText(gamepadView.isGridVisible()
                ? R.string.xstreaming_toolbar_grid_on
                : R.string.xstreaming_toolbar_grid_off);
    }

    /**
     * Per-profile joystick mode override (free/fixed). {@link XSProfileStore#setJoystickMode}
     * already existed but had no UI calling it before this toolbar; only the mock-preview
     * activity toggled the pad's mode directly without persisting a choice per profile.
     */
    private void showStickSettingsDialog() {
        final String[] items = {
                context.getString(R.string.xstreaming_stick_mode_free),
                context.getString(R.string.xstreaming_stick_mode_fixed),
        };
        int current = resolveJoystickMode();
        int checkedIndex = current == 0 ? 1 : 0;
        new AlertDialog.Builder(context)
                .setTitle(R.string.xstreaming_stick_settings_title)
                .setSingleChoiceItems(items, checkedIndex, (dialog, which) -> {
                    int mode = which == 0 ? 1 : 0;
                    store.setJoystickMode(activeProfile, mode);
                    gamepadView.setJoystickMode(mode);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- Per-element (button / stick) configuration ----

    /**
     * Right-docked sliding panel replacing the old per-element AlertDialog, so adjusting a
     * button no longer covers the pad it belongs to. Content is identical to the former
     * dialog; only the container and its show/hide mechanics changed.
     */
    private void showElementConfigPanel(final XSButtonConfig cfg) {
        boolean isStick = XSGamepadLayout.LEFT_STICK.equals(cfg.name)
                || XSGamepadLayout.RIGHT_STICK.equals(cfg.name);
        boolean isMacro = XSGamepadLayout.isMacroButtonName(cfg.name);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText(cfg.name);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(16);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleParams);
        header.addView(title);
        Button closeButton = new Button(context);
        closeButton.setText("✕");
        closeButton.setOnClickListener(v -> hideElementConfigPanel());
        header.addView(closeButton);
        root.addView(header);

        Switch showSwitch = new Switch(context);
        showSwitch.setText(R.string.xstreaming_config_show);
        showSwitch.setChecked(cfg.show);
        showSwitch.setOnCheckedChangeListener((b, checked) -> {
            cfg.show = checked;
            persistLayout();
            gamepadView.setLayout(layout);
            gamepadView.setEditMode(true);
        });
        root.addView(showSwitch);

        if (!isStick) {
            final TextView sizeLabel = new TextView(context);
            updateSizeLabel(sizeLabel, cfg.scale);
            root.addView(sizeLabel);
            SeekBar sizeSeek = new SeekBar(context);
            sizeSeek.setMax(35); // 0.5x .. 4.0x, in 0.1x steps, matching XStreaming's step
            sizeSeek.setProgress(Math.round((cfg.scale - 0.5f) * 10));
            sizeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    cfg.scale = 0.5f + progress / 10f;
                    updateSizeLabel(sizeLabel, cfg.scale);
                    persistLayout();
                    gamepadView.setLayout(layout);
                    gamepadView.setEditMode(true);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
            root.addView(sizeSeek);

            Switch turboSwitch = new Switch(context);
            turboSwitch.setText(R.string.xstreaming_config_turbo);
            turboSwitch.setChecked(cfg.turbo);
            turboSwitch.setOnCheckedChangeListener((b, checked) -> {
                cfg.turbo = checked;
                persistLayout();
            });
            root.addView(turboSwitch);

            // XStreaming also excludes Nexus from toggle-hold, alongside sticks and macros.
            if (!isMacro && !"Nexus".equals(cfg.name)) {
                Switch holdSwitch = new Switch(context);
                holdSwitch.setText(R.string.xstreaming_config_hold);
                holdSwitch.setChecked(cfg.holdToggle);
                holdSwitch.setOnCheckedChangeListener((b, checked) -> {
                    cfg.holdToggle = checked;
                    persistLayout();
                });
                root.addView(holdSwitch);
            }
        }

        if (isMacro) {
            Switch loopSwitch = new Switch(context);
            loopSwitch.setText(R.string.xstreaming_config_loop);
            loopSwitch.setChecked(cfg.macroLoopEnabled);
            loopSwitch.setOnCheckedChangeListener((b, checked) -> {
                cfg.macroLoopEnabled = checked;
                persistLayout();
            });
            root.addView(loopSwitch);

            final TextView intervalLabel = new TextView(context);
            updateMsLabel(intervalLabel, R.string.xstreaming_config_loop_interval, cfg.macroLoopIntervalMs);
            root.addView(intervalLabel);
            SeekBar intervalSeek = new SeekBar(context);
            intervalSeek.setMax(200); // 0..10000ms in 50ms steps, matching XStreaming's step
            intervalSeek.setProgress(cfg.macroLoopIntervalMs / 50);
            intervalSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    // Only the label follows the drag; XStreaming defers writing the new
                    // interval until release so it doesn't reschedule an actively-looping
                    // macro's timer on every tick.
                    updateMsLabel(intervalLabel, R.string.xstreaming_config_loop_interval, progress * 50);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    cfg.macroLoopIntervalMs = seekBar.getProgress() * 50;
                    persistLayout();
                }
            });
            root.addView(intervalSeek);

            Button editStepsButton = new Button(context);
            editStepsButton.setText(R.string.xstreaming_config_edit_macro);
            editStepsButton.setOnClickListener(v -> XSMacroEditorDialog.show(context, cfg.macroSteps,
                    steps -> {
                        cfg.macroSteps = steps;
                        persistLayout();
                    }));
            root.addView(editStepsButton);
        }

        Button resetPositionButton = new Button(context);
        resetPositionButton.setText(R.string.xstreaming_config_reset_position);
        resetPositionButton.setOnClickListener(v -> {
            int wPx = parentLayout.getWidth();
            int hPx = parentLayout.getHeight();
            if (wPx <= 0 || hPx <= 0) {
                return;
            }
            float density = context.getResources().getDisplayMetrics().density;
            List<XSButtonConfig> defaults = XSGamepadLayout.buildDefaultLayout(
                    Math.round(wPx / density), Math.round(hPx / density));
            for (XSButtonConfig d : defaults) {
                if (d.name.equals(cfg.name)) {
                    cfg.x = d.x;
                    cfg.y = d.y;
                    break;
                }
            }
            persistLayout();
            gamepadView.setLayout(layout);
            gamepadView.setEditMode(true);
        });
        root.addView(resetPositionButton);

        elementPanel.removeAllViews();
        elementPanel.addView(root);
        showElementPanelAnimated();
    }

    private void showElementPanelAnimated() {
        if (elementPanel.getVisibility() != View.VISIBLE) {
            elementPanel.setTranslationX(panelWidthPx);
            elementPanel.setVisibility(View.VISIBLE);
            elementPanel.animate().translationX(0).setDuration(150).start();
        }
    }

    private void hideElementConfigPanel() {
        if (elementPanel.getVisibility() != View.VISIBLE) {
            return;
        }
        elementPanel.animate().translationX(panelWidthPx).setDuration(150)
                .withEndAction(() -> elementPanel.setVisibility(View.GONE))
                .start();
    }

    private int dp(int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    private void updateSizeLabel(TextView label, float scale) {
        label.setText(context.getString(R.string.xstreaming_config_size)
                + String.format(java.util.Locale.US, ": %.1fx", scale));
    }

    private void updateCoverSizeLabel(TextView label, float size) {
        label.setText(context.getString(R.string.xstreaming_config_size)
                + String.format(java.util.Locale.US, ": %.2f", size));
    }

    private void updateMsLabel(TextView label, int stringRes, int valueMs) {
        label.setText(context.getString(stringRes) + ": " + valueMs + "ms");
    }
}
