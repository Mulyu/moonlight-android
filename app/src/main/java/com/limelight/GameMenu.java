package com.limelight;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.binding.input.GameInputDevice;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.KeyConfigHelper;
import com.limelight.utils.KeyMapper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provide options for ongoing Game Stream.
 * <p>
 * Shown on back action in game activity.
 */
public class GameMenu implements Game.GameMenuCallbacks {

    public static final long KEY_UP_DELAY = 25;
    private static final long TEST_GAME_FOCUS_DELAY = 10;

    public static final String PREF_NAME = "specialPrefs"; // SharedPreferences的名称

    public static final String KEY_NAME = "special_key"; // 要保存的键名称

    public static class MenuOption {
        private final String label;
        private final boolean withGameFocus;
        private final Runnable runnable;

        public MenuOption(String label, boolean withGameFocus, Runnable runnable) {
            this.label = label;
            this.withGameFocus = withGameFocus;
            this.runnable = runnable;
        }

        public MenuOption(String label, Runnable runnable) {
            this(label, false, runnable);
        }
    }

    private final Game game;
    private final Context dialogScreenContext;

    /** Right-docked panel replacing the old AlertDialog-list menus; see {@link #showMenuPanel}. */
    private ScrollView menuPanel;
    private int panelWidthPx = -1;

    public GameMenu(Game game, Context dialogScreenContext) {
        this.game = game;
        this.dialogScreenContext = dialogScreenContext;
    }

    public GameMenu(Game game) {
        this.game = game;
        this.dialogScreenContext = game;
    }

    private String getString(int id) {
        return game.getResources().getString(id);
    }


    private void sendKeys(short[] keys) {
        game.sendKeys(keys);
    }

    private void runWithGameFocus(Runnable runnable) {
        // Ensure that the Game activity is still active (not finished)
        if (game.isFinishing()) {
            return;
        }
        // Check if the game window has focus again, if not try again after delay
        if (!game.hasWindowFocus() && dialogScreenContext instanceof Game) {
            new Handler().postDelayed(() -> runWithGameFocus(runnable), TEST_GAME_FOCUS_DELAY);
            return;
        }
        // Game Activity has focus, run runnable
        runnable.run();
    }

    private void run(MenuOption option) {
        if (option.runnable == null) {
            return;
        }

        if (option.withGameFocus) {
            runWithGameFocus(option.runnable);
        } else {
            option.runnable.run();
        }
    }

    private int dp(int value) {
        return Math.round(dialogScreenContext.getResources().getDisplayMetrics().density * value);
    }

    /**
     * The panel is attached to whichever activity {@link #dialogScreenContext} actually is --
     * normally {@link #game}, but {@link com.limelight.utils.ExternalDisplayControlActivity}
     * passes itself instead when the menu is shown on a cover/outer display, exactly like the
     * old AlertDialog's themed context did.
     */
    private void ensurePanelAttached() {
        if (menuPanel != null) {
            return;
        }
        Activity hostActivity = dialogScreenContext instanceof Activity
                ? (Activity) dialogScreenContext : game;
        ViewGroup root = hostActivity.findViewById(android.R.id.content);

        panelWidthPx = dp(300);
        menuPanel = new ScrollView(dialogScreenContext);
        menuPanel.setBackgroundColor(0xF2171B24);
        menuPanel.setVisibility(View.GONE);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                panelWidthPx, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.END);
        root.addView(menuPanel, params);
    }

    private void showPanel() {
        if (menuPanel.getVisibility() != View.VISIBLE) {
            menuPanel.setTranslationX(panelWidthPx);
            menuPanel.setVisibility(View.VISIBLE);
            menuPanel.animate().translationX(0).setDuration(150).start();
        }
    }

    /**
     * A tap either runs a leaf action (the panel is left open afterward, so several quick
     * actions -- e.g. toggling HUD then rotating the screen -- don't each require reopening the
     * menu, unlike the old one-shot AlertDialog) or a Runnable that calls back into
     * {@link #showMenuPanel} itself (Advanced / Send special keys / Server Commands), which
     * replaces the panel's content in place for drill-down navigation. The one special case is
     * "Cancel" (a null runnable), which explicitly closes the panel.
     */
    private void onOptionSelected(MenuOption option) {
        if (option.runnable == null) {
            hideMenu();
            return;
        }
        run(option);
    }

    private void showMenuPanel(String title, MenuOption[] options) {
        ensurePanelAttached();

        LinearLayout content = new LinearLayout(dialogScreenContext);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        content.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(dialogScreenContext);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = new TextView(dialogScreenContext);
        titleView.setText(title);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(16);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleView.setLayoutParams(titleParams);
        header.addView(titleView);
        Button closeButton = new Button(dialogScreenContext);
        closeButton.setText("✕");
        closeButton.setOnClickListener(v -> hideMenu());
        header.addView(closeButton);
        content.addView(header);

        for (final MenuOption option : options) {
            Button row = new Button(dialogScreenContext);
            row.setText(option.label);
            row.setAllCaps(false);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setBackgroundColor(0x14FFFFFF);
            row.setTextColor(0xFFFFFFFF);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = dp(6);
            row.setLayoutParams(rowParams);
            row.setOnClickListener(v -> onOptionSelected(option));
            content.addView(row);
        }

        menuPanel.removeAllViews();
        menuPanel.addView(content);
        showPanel();
    }

    private void showSpecialKeysMenu() {
        List<MenuOption> options = new ArrayList<>();

        if(!PreferenceConfiguration.readPreferences(game).disableDefaultExtraKeys){
            options.add(new MenuOption(getString(R.string.game_menu_send_keys_esc),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_ESCAPE})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_f11),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_F11})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_alt_f4),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_F4})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_alt_enter),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_RETURN})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_ctrl_v),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_V})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_win),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_win_d),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_D})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_win_g),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_G})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_ctrl_alt_tab),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_TAB})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_shift_tab),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_TAB})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_win_shift_left),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_LEFT})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_ctrl_alt_shift_f1),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL,KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_F1})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_ctrl_alt_shift_f12),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL,KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_F12})));

            options.add(new MenuOption(getString(R.string.game_menu_send_keys_alt_b),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_B})));
        }

        // Import custom shortcuts
        SharedPreferences preferences = game.getSharedPreferences(PREF_NAME, Activity.MODE_PRIVATE);
        String value = preferences.getString(KEY_NAME,"");

        if(!TextUtils.isEmpty(value)){
            try {
                KeyConfigHelper.ShortcutFile shortcutFile = KeyConfigHelper.parseShortcutFile(value);
                if (shortcutFile != null && shortcutFile.data != null && !shortcutFile.data.isEmpty()) {
                    List<KeyConfigHelper.Shortcut> data = shortcutFile.data;
                    for (KeyConfigHelper.Shortcut sc : data) {
                        List<String> keys = sc.keys;
                        short[] keyCodes = new short[keys.size()];

                        for (int i = 0; i < keys.size(); i++) {
                            String code = keys.get(i);
                            int keycode;

                            if (code.startsWith("0x")) {               // literal hex value
                                keycode = Integer.parseInt(code.substring(2), 16);
                            } else if (code.startsWith("VK_")) {       // symbolic constant in KeyMapper
                                Field field = KeyMapper.class.getDeclaredField(code);
                                keycode = field.getInt(null);
                            } else {                                   // unsupported
                                throw new IllegalArgumentException("Unknown key code: " + code);
                            }
                            keyCodes[i] = (short) keycode;
                        }

                        // Whatever MenuOption looks like in your project
                        MenuOption option = new MenuOption(sc.name, () -> sendKeys(keyCodes));
                        options.add(option);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(game,getString(R.string.wrong_import_format),Toast.LENGTH_SHORT).show();
            }
        }
        options.add(new MenuOption(getString(R.string.game_menu_cancel), null));

        showMenuPanel(getString(R.string.game_menu_send_keys), options.toArray(new MenuOption[options.size()]));
    }

    private void showAdvancedMenu(GameInputDevice device) {
        List<MenuOption> options = new ArrayList<>();
        if (game.allowChangeMouseMode) {
            options.add(new MenuOption(getString(R.string.game_menu_select_mouse_mode), true, () -> game.selectMouseMode(dialogScreenContext)));
        }
        
        options.add(new MenuOption(getString(R.string.game_menu_toggle_hud), true, game::toggleHUD));
        options.add(new MenuOption(getString(R.string.game_menu_toggle_floating_button), true, game::toggleFloatingButtonVisibility));
        options.add(new MenuOption(getString(R.string.game_menu_toggle_keyboard_model), true, game::toggleKeyboardController));
        if (!game.isOnExternalDisplay()) {
            options.add(new MenuOption(getString(R.string.game_menu_toggle_virtual_model), true, game::toggleVirtualController));
        }
        options.add(new MenuOption(getString(R.string.game_menu_toggle_virtual_keyboard_model), true, game::toggleFullKeyboard));
        options.add(new MenuOption(getString(R.string.game_menu_task_manager), true, () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_ESCAPE})));

        // **FIXED:** This is a UI navigation action, so it should not use withGameFocus.
        // No explicit hideMenu() here: showSpecialKeysMenu() -> showMenuPanel() replaces this
        // panel's content in place, so closing it first would just cause a needless
        // close-then-reopen flicker.
        options.add(new MenuOption(getString(R.string.game_menu_send_keys), this::showSpecialKeysMenu));

        options.add(new MenuOption(getString(R.string.game_menu_switch_touch_sensitivity_model), true, game::switchTouchSensitivity));
        if (device != null) {
            options.addAll(device.getGameMenuOptions());
        }
        options.add(new MenuOption(getString(R.string.game_menu_cancel), null));
        showMenuPanel(getString(R.string.game_menu_advanced), options.toArray(new MenuOption[options.size()]));
    }

    private void showServerCmd(ArrayList<String> serverCmds) {
        List<MenuOption> options = new ArrayList<>();

        AtomicInteger index = new AtomicInteger(0);
        for (String str : serverCmds) {
            final int finalI = index.getAndIncrement();
            options.add(new MenuOption("> " + str, true, () -> game.sendExecServerCmd(finalI)));
        };

        options.add(new MenuOption(getString(R.string.game_menu_cancel), null));

        showMenuPanel(getString(R.string.game_menu_server_cmd), options.toArray(new MenuOption[options.size()]));
    }

    public void showMenu(GameInputDevice device) {
        List<MenuOption> options = new ArrayList<>();

        options.add(new MenuOption(getString(R.string.game_menu_disconnect), game::disconnect));

        options.add(new MenuOption(getString(R.string.game_menu_quit_session), game::quit));

        options.add(new MenuOption(getString(R.string.game_menu_upload_clipboard), true,
                () -> game.sendClipboard(true)));

        options.add(new MenuOption(getString(R.string.game_menu_fetch_clipboard), true,
                () -> game.getClipboard(0)));

        options.add(new MenuOption(getString(R.string.game_menu_server_cmd), true,
                () -> {
                    ArrayList<String> serverCmds = game.getServerCmds();
                    if (serverCmds.isEmpty()) {
                        int themeResId = game.getApplicationInfo().theme;
                        Context themedContext = new ContextThemeWrapper(dialogScreenContext, themeResId);
                        new AlertDialog.Builder(themedContext)
                                .setTitle(R.string.game_dialog_title_server_cmd_empty)
                                .setMessage(R.string.game_dialog_message_server_cmd_empty)
                                .show();
                    } else {
                        // No explicit hideMenu(): showServerCmd() -> showMenuPanel() replaces
                        // this panel's content in place.
                        this.showServerCmd(serverCmds);
                    }
                }));

        options.add(new MenuOption(getString(R.string.game_menu_toggle_keyboard), true,
                game::toggleKeyboard));

        options.add(new MenuOption(getString(game.isZoomModeEnabled() ? R.string.game_menu_disable_zoom_mode : R.string.game_menu_enable_zoom_mode), true,
                game::toggleZoomMode));

        if (dialogScreenContext == game) {
            options.add(new MenuOption(getString(R.string.game_menu_rotate_screen), true,
                    game::rotateScreen));
        }

        options.add(new MenuOption(getString(R.string.game_menu_advanced), true,
                () -> showAdvancedMenu(device)));

        // Opens the virtual controller's own layout editor (a separate right-docked panel),
        // so this one is closed first rather than left showing behind/alongside it.
        options.add(new MenuOption(getString(R.string.game_menu_controller_layout), () -> {
            hideMenu();
            game.openControllerLayoutEditor();
        }));

        options.add(new MenuOption(getString(R.string.game_menu_cancel), null));

        showMenuPanel(getString(R.string.quick_menu_title), options.toArray(new MenuOption[options.size()]));
    }

    @Override
    public void hideMenu() {
        if (menuPanel == null || menuPanel.getVisibility() != View.VISIBLE) {
            return;
        }
        menuPanel.animate().translationX(panelWidthPx).setDuration(150)
                .withEndAction(() -> menuPanel.setVisibility(View.GONE))
                .start();
    }

    @Override
    public boolean isMenuOpen() {
        return menuPanel != null && menuPanel.getVisibility() == View.VISIBLE;
    }
}
