package com.limelight.binding.input.virtual_controller.xstreaming;

import android.view.View;

/**
 * Immutable 2D vector used by the XStreaming-style touch controls.
 *
 * Ported from XStreaming (com.xstreaming.touchcontrols.Vector).
 */
public class XSVector {
    public final float x;
    public final float y;

    public XSVector(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public XSVector plus(XSVector o) {
        return new XSVector(this.x + o.x, this.y + o.y);
    }

    public XSVector minus(XSVector o) {
        return new XSVector(this.x - o.x, this.y - o.y);
    }

    public XSVector times(float s) {
        return new XSVector(this.x * s, this.y * s);
    }

    public XSVector divide(float s) {
        return this.times(1f / s);
    }

    public float getLengthSq() {
        return x * x + y * y;
    }

    public float getLength() {
        return (float) Math.sqrt(getLengthSq());
    }

    public XSVector getNormalized() {
        float length = getLength();
        if (length == 0) {
            return new XSVector(0, 0);
        }
        return this.divide(length);
    }

    public static XSVector getLocationOnScreen(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new XSVector(location[0], location[1]);
    }

    @Override
    public String toString() {
        return "XSVector(" + x + ", " + y + ")";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        XSVector vector = (XSVector) obj;
        return Float.compare(vector.x, x) == 0 && Float.compare(vector.y, y) == 0;
    }

    @Override
    public int hashCode() {
        int result = Float.hashCode(x);
        result = 31 * result + Float.hashCode(y);
        return result;
    }
}
