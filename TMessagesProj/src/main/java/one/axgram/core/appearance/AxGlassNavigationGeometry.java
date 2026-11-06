package one.axgram.core.appearance;

public final class AxGlassNavigationGeometry {
    public static final int EXTRA_TOP_DP = 12;
    private AxGlassNavigationGeometry() {
    }

    public static float clampCenter(float center, float left, float right, float radius) {
        if (!isFinite(center) || right <= left) return (left + right) / 2f;
        float inset = Math.min(Math.max(0, radius), (right - left) / 2f);
        return Math.max(left + inset, Math.min(right - inset, center));
    }

    public static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
