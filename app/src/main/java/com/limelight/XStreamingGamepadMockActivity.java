package com.limelight;

import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.limelight.binding.input.virtual_controller.xstreaming.XSGamepadLayout;
import com.limelight.binding.input.virtual_controller.xstreaming.XStreamingGamepadView;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Mock screen for the XStreaming touch controller ported into Moonlight.
 *
 * It renders the pad exactly as {@link XStreamingGamepadView} will render it
 * over a stream, and echoes every button and stick event back on screen. Nothing
 * here is wired into {@code ControllerHandler} or the streaming stack yet: this
 * exists so the layout, artwork, hit-boxes and stick feel can be judged on a
 * real device before the input plumbing is written.
 */
public class XStreamingGamepadMockActivity extends AppCompatActivity {

    private XStreamingGamepadView gamepad;
    private TextView buttonsText;
    private TextView sticksText;
    private Button stickModeButton;
    private Button hapticsButton;
    private TextView opacityLabel;

    /** Insertion-ordered so the readout lists buttons in the order they were pressed. */
    private final Set<String> pressedButtons = new LinkedHashSet<>();

    private float leftStickX, leftStickY, rightStickX, rightStickY;
    private boolean hapticsEnabled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_xstreaming_gamepad_mock);

        gamepad = findViewById(R.id.xstreaming_mock_gamepad);
        buttonsText = findViewById(R.id.xstreaming_mock_buttons);
        sticksText = findViewById(R.id.xstreaming_mock_sticks);
        stickModeButton = findViewById(R.id.xstreaming_mock_stick_mode);
        hapticsButton = findViewById(R.id.xstreaming_mock_haptics);
        opacityLabel = findViewById(R.id.xstreaming_mock_opacity_label);

        SeekBar opacityBar = findViewById(R.id.xstreaming_mock_opacity);

        gamepad.setListener(new XStreamingGamepadView.Listener() {
            @Override
            public void onButtonStateChanged(String buttonName, boolean pressed) {
                if (pressed) {
                    pressedButtons.add(buttonName);
                } else {
                    pressedButtons.remove(buttonName);
                }
                updateButtonsText();
            }

            @Override
            public void onStickMoved(String stickId, float x, float y) {
                if (XStreamingGamepadView.STICK_LEFT.equals(stickId)) {
                    leftStickX = x;
                    leftStickY = y;
                } else {
                    rightStickX = x;
                    rightStickY = y;
                }
                updateSticksText();
            }

            @Override
            public void onElementMoved(String name, int xDp, int yDp) {
            }

            @Override
            public void onElementTapped(String name) {
            }
        });

        gamepad.post(() -> {
            int wPx = gamepad.getWidth();
            int hPx = gamepad.getHeight();
            if (wPx <= 0 || hPx <= 0) {
                return;
            }
            float density = getResources().getDisplayMetrics().density;
            gamepad.setLayout(XSGamepadLayout.buildDefaultLayout(
                    Math.round(wPx / density), Math.round(hPx / density)));
        });

        stickModeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gamepad.setJoystickMode(gamepad.getJoystickMode() == 0 ? 1 : 0);
                gamepad.setHapticsEnabled(hapticsEnabled);
                updateStickModeButton();
            }
        });

        hapticsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hapticsEnabled = !hapticsEnabled;
                gamepad.setHapticsEnabled(hapticsEnabled);
                updateHapticsButton();
            }
        });

        opacityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                gamepad.setControlOpacity(progress / 100f);
                updateOpacityLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        opacityBar.setProgress((int) (gamepad.getControlOpacity() * 100));

        updateButtonsText();
        updateSticksText();
        updateStickModeButton();
        updateHapticsButton();
        updateOpacityLabel();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void updateButtonsText() {
        String pressed = pressedButtons.isEmpty()
                ? getString(R.string.xstreaming_mock_no_buttons)
                : android.text.TextUtils.join(", ", pressedButtons);
        buttonsText.setText(getString(R.string.xstreaming_mock_buttons_format, pressed));
    }

    private void updateSticksText() {
        sticksText.setText(String.format(Locale.US,
                getString(R.string.xstreaming_mock_sticks_format),
                leftStickX, leftStickY, rightStickX, rightStickY));
    }

    private void updateStickModeButton() {
        stickModeButton.setText(gamepad.getJoystickMode() == 0
                ? R.string.xstreaming_mock_stick_mode_fixed
                : R.string.xstreaming_mock_stick_mode_free);
    }

    private void updateHapticsButton() {
        hapticsButton.setText(hapticsEnabled
                ? R.string.xstreaming_mock_haptics_on
                : R.string.xstreaming_mock_haptics_off);
    }

    private void updateOpacityLabel() {
        opacityLabel.setText(getString(R.string.xstreaming_mock_opacity_format,
                Math.round(gamepad.getControlOpacity() * 100)));
    }
}
