package com.limelight.binding.input.virtual_controller.xstreaming;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The small set of buttons shown on a foldable's cover (outer) display while
 * it's closed, so a stream can keep taking a few basic inputs (by default the
 * two shoulders and triggers) without opening the phone.
 *
 * Position and size come from {@link XSCoverButton}, stored as fractions of
 * this view's own size, so the same layout works whatever the cover panel's
 * actual resolution turns out to be. Ported from XStreaming
 * (features/controller-customization/model/coverLayout.ts's rendering, folded
 * into a plain native view since there is no JS runtime on this side).
 */
public class XSCoverGamepadView extends FrameLayout {

    public interface Listener {
        void onButtonStateChanged(String buttonName, boolean pressed);
    }

    private List<XSCoverButton> layout;
    private final Map<String, XSCoverButton> configByName = new LinkedHashMap<>();
    private final Map<String, XSButtonView> buttonViews = new LinkedHashMap<>();
    private Listener listener;

    public XSCoverGamepadView(Context context) {
        this(context, null);
    }

    public XSCoverGamepadView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public XSCoverGamepadView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setClickable(false);
        setFocusable(false);
        // This is the root content view of a brand-new WindowAreaSessionPresenter session on
        // the cover display -- there's no game video behind it like there is for the main pad
        // overlay, so without an explicit background it falls through to the window's default
        // (white), and these buttons' light-colored artwork (designed to sit over dark video)
        // becomes nearly invisible.
        setBackgroundColor(Color.BLACK);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setLayout(List<XSCoverButton> newLayout) {
        this.layout = newLayout;
        rebuild();
    }

    private void rebuild() {
        removeAllViews();
        buttonViews.clear();
        configByName.clear();
        if (layout == null) {
            return;
        }
        for (final XSCoverButton cfg : layout) {
            if (!cfg.show) {
                continue;
            }
            String drawableName = XSGamepadLayout.drawableNameFor(cfg.name);
            if (drawableName == null) {
                continue;
            }
            XSButtonView button = new XSButtonView(getContext());
            button.setButtonName(drawableName);
            button.setTag(cfg.name);
            button.setOnButtonStateChangeListener((view, pressed) -> {
                if (listener != null) {
                    listener.onButtonStateChanged(cfg.name, pressed);
                }
            });
            addView(button, new LayoutParams(0, 0, Gravity.TOP | Gravity.START));
            buttonViews.put(cfg.name, button);
            configByName.put(cfg.name, cfg);
        }
        repositionAll();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        repositionAll();
    }

    private void repositionAll() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        for (Map.Entry<String, XSButtonView> entry : buttonViews.entrySet()) {
            XSCoverButton cfg = configByName.get(entry.getKey());
            if (cfg == null) {
                continue;
            }
            int size = Math.round(cfg.size * width);
            LayoutParams params = (LayoutParams) entry.getValue().getLayoutParams();
            params.width = size;
            params.height = size;
            params.setMargins(Math.round(cfg.x * width), Math.round(cfg.y * height), 0, 0);
            entry.getValue().setLayoutParams(params);
        }
    }

    /** Releases any button currently held, so nothing stays stuck when the cover session ends. */
    public void releaseAll() {
        for (XSButtonView button : buttonViews.values()) {
            if (button.isButtonPressed()) {
                // Reconstruct a synthetic release: the view has no public
                // "release" call, so cancel its touch stream instead.
                button.dispatchTouchEvent(android.view.MotionEvent.obtain(
                        android.os.SystemClock.uptimeMillis(),
                        android.os.SystemClock.uptimeMillis(),
                        android.view.MotionEvent.ACTION_CANCEL, 0, 0, 0));
            }
        }
    }
}
