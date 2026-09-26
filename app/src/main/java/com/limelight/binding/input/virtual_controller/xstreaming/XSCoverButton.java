package com.limelight.binding.input.virtual_controller.xstreaming;

/**
 * One button shown on a foldable's cover (outer) display while it is closed.
 * Position and size are fractions of the cover display's own width/height (not
 * pixels), so the same layout maps onto whatever the cover panel's actual
 * resolution turns out to be.
 *
 * Ported from XStreaming (features/controller-customization/model/coverLayout.ts's
 * CoverButton).
 */
public class XSCoverButton {
    public String name;
    public String label;
    public float x;
    public float y;
    /** Square side length, as a fraction of the cover display's width. */
    public float size;
    public boolean show = true;

    public XSCoverButton() {
    }

    public XSCoverButton(String name, String label, float x, float y, float size) {
        this.name = name;
        this.label = label;
        this.x = x;
        this.y = y;
        this.size = size;
    }

    public XSCoverButton copy() {
        XSCoverButton c = new XSCoverButton(name, label, x, y, size);
        c.show = show;
        return c;
    }
}
