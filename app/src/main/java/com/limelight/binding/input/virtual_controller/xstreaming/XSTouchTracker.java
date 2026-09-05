package com.limelight.binding.input.virtual_controller.xstreaming;

import android.view.MotionEvent;

/**
 * Tracks a single pointer across a multi-touch gesture and reports its position.
 *
 * The first pointer to go down inside the view owns the tracker until it is
 * lifted; other pointers are ignored, so a second finger landing on an analog
 * stick cannot steal the stick from the finger already holding it.
 *
 * Ported from XStreaming (com.xstreaming.touchcontrols.TouchTracker).
 */
public class XSTouchTracker {
    private XSVector currentPosition;
    private int pointerId = -1;
    private PositionChangedCallback positionChangedCallback;

    public interface PositionChangedCallback {
        /** @param position current pointer position, or null once the pointer is released. */
        void onPositionChanged(XSVector position);
    }

    public void setPositionChangedCallback(PositionChangedCallback callback) {
        this.positionChangedCallback = callback;
    }

    public XSVector getCurrentPosition() {
        return currentPosition;
    }

    private void setCurrentPosition(XSVector value) {
        this.currentPosition = value;
        if (positionChangedCallback != null) {
            positionChangedCallback.onPositionChanged(currentPosition);
        }
    }

    public void touchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (pointerId == -1) {
                    pointerId = event.getPointerId(event.getActionIndex());
                    setCurrentPosition(new XSVector(
                            event.getX(event.getActionIndex()),
                            event.getY(event.getActionIndex())));
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pointerId != -1) {
                    if (event.getActionMasked() == MotionEvent.ACTION_CANCEL ||
                            event.getPointerId(event.getActionIndex()) == pointerId) {
                        pointerId = -1;
                        setCurrentPosition(null);
                    }
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (pointerId != -1) {
                    int pointerIndex = event.findPointerIndex(pointerId);
                    if (pointerIndex >= 0) {
                        setCurrentPosition(new XSVector(
                                event.getX(pointerIndex),
                                event.getY(pointerIndex)));
                    }
                }
                break;
        }
    }
}
