package one.axgram.core.appearance;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

public final class AxGlassNavigationDrawable extends Drawable implements Drawable.Callback {
    private final Drawable source;
    private final Theme.ResourcesProvider resources;
    private final Path surface = new Path();
    private final Path cutout = new Path();
    private final RectF bounds = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float center;
    private int alpha = 255;

    public AxGlassNavigationDrawable(Drawable source, Theme.ResourcesProvider resources) {
        this.source = source;
        this.resources = resources;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(AndroidUtilities.dpf2(.75f));
    }

    public void attachSource() {
        source.setCallback(this);
    }

    public Drawable detachSource() {
        source.setCallback(null);
        return source;
    }

    public void setCenter(float center) {
        if (this.center != center) {
            this.center = center;
            invalidateSelf();
        }
    }

    @Override
    protected void onBoundsChange(Rect rect) {
        source.setBounds(rect);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        float dp = AndroidUtilities.density;
        Rect rect = getBounds();
        bounds.set(rect.left + 8 * dp, rect.top + 22 * dp, rect.right - 8 * dp, rect.bottom - 8 * dp);
        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        float x = AxGlassNavigationGeometry.clampCenter(center, bounds.left, bounds.right, 24 * dp);
        float y = bounds.top;
        surface.rewind();
        surface.addRoundRect(bounds, 27 * dp, 27 * dp, Path.Direction.CW);
        cutout.rewind();
        cutout.moveTo(x - 34 * dp, y - 40 * dp);
        cutout.lineTo(x - 34 * dp, y);
        cutout.cubicTo(x - 25 * dp, y, x - 26 * dp, y + 24 * dp, x, y + 24 * dp);
        cutout.cubicTo(x + 26 * dp, y + 24 * dp, x + 25 * dp, y, x + 34 * dp, y);
        cutout.lineTo(x + 34 * dp, y - 40 * dp);
        cutout.close();
        surface.op(cutout, Path.Op.DIFFERENCE);
        int save = canvas.save();
        canvas.clipPath(surface);
        source.draw(canvas);
        if (AxAppearance.isGlassTheme()) {
            // Match the folders' tint over the same native glass backing; geometry stays intact.
            fill.setColor(Theme.multAlpha(AxFolderTabsStyle.panelColor(resources), alpha / 255f));
            canvas.drawPath(surface, fill);
        }
        canvas.restoreToCount(save);
        int color = Theme.getColor(Theme.key_windowBackgroundWhite, resources);
        int circleColor = AxAppearance.isGlassTheme() ? AxFolderTabsStyle.panelColor(resources)
                : AxAppearance.glassColor(ColorUtils.setAlphaComponent(color, 224));
        fill.setColor(Theme.multAlpha(circleColor, alpha / 255f));
        canvas.drawCircle(x, y, 20 * dp, fill);
        stroke.setColor(Theme.multAlpha(Theme.getColor(Theme.key_divider, resources), alpha / 255f));
        canvas.drawPath(surface, stroke);
        canvas.drawCircle(x, y, 20 * dp, stroke);
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        source.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter filter) {
        source.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void invalidateDrawable(@NonNull Drawable who) {
        invalidateSelf();
    }

    @Override
    public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
        scheduleSelf(what, when);
    }

    @Override
    public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
        unscheduleSelf(what);
    }
}
