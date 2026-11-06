package one.axgram.core.appearance;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import org.telegram.ui.ActionBar.Theme;
import java.io.InputStream;

/** Approved glass wallpaper under a quiet reading surface. Shared sampled bitmaps, no live blur. */
public final class AxChatListBackdrop extends Drawable {
    private static final Bitmap[] wallpapers = new Bitmap[2];
    private static final boolean[] attempted = new boolean[2];
    private final Context context;
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint wash = new Paint();
    private final RectF destination = new RectF();
    private int alpha = 255;
    private Integer paletteColor;

    public void setPaletteColor(int color) { paletteColor = color; invalidateSelf(); }

    public static org.telegram.ui.ActionBar.ThemeDescription colorBinding(android.view.View view) {
        return new org.telegram.ui.ActionBar.ThemeDescription(null, 0, null, null, null, null, Theme.key_windowBackgroundWhite) {
            @Override public void setColor(int color, boolean useDefault, boolean save) {
                super.setColor(color, useDefault, save);
                if (view.getBackground() instanceof AxChatListBackdrop) {
                    ((AxChatListBackdrop) view.getBackground()).setPaletteColor(color);
                    view.invalidate();
                }
            }
        };
    }

    public static float darkFraction(int color) {
        return (float) Math.max(0, Math.min(1, (.65 - ColorUtils.calculateLuminance(color | 0xff000000)) / .53));
    }

    public AxChatListBackdrop(Context context) { this.context = context.getApplicationContext(); }

    @Override public void draw(@NonNull Canvas canvas) {
        if (alpha == 0) return;
        Rect b = getBounds();
        int save = canvas.save();
        canvas.clipRect(b);
        if (alpha != 255) canvas.saveLayerAlpha(b.left, b.top, b.right, b.bottom, alpha);
        try { drawContent(canvas); } finally { canvas.restoreToCount(save); }
    }

    private void drawContent(Canvas canvas) {
        Rect b = getBounds();
        int base = paletteColor != null ? paletteColor : Theme.getColor(Theme.key_windowBackgroundWhite);
        wash.setColor(base); canvas.drawRect(b, wash);
        if (!AxAppearance.isGlassTheme() || !AxAppearance.listWallpaperVisible() || b.isEmpty()) return;
        float dark = darkFraction(base);
        drawWallpaper(canvas, b, 0, 255);
        drawWallpaper(canvas, b, 1, Math.round(255 * dark));
        wash.setColor(ColorUtils.setAlphaComponent(base, Math.round(224 + 14 * dark)));
        canvas.drawRect(b, wash);
    }

    private void drawWallpaper(Canvas canvas, Rect b, int i, int alpha) {
        if (alpha == 0) return;
        if (!attempted[i]) {
            attempted[i] = true;
            try (InputStream stream = context.getAssets().open("axgram/list-" + (i == 1 ? "dark" : "light") + ".png")) {
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 4;
                wallpapers[i] = BitmapFactory.decodeStream(stream, null, options);
            } catch (Exception ignored) { /* The native theme color remains the fallback. */ }
        }
        Bitmap bitmap = wallpapers[i];
        if (bitmap == null) return;
        float scale = Math.max(b.width() / (float) bitmap.getWidth(), b.height() / (float) bitmap.getHeight());
        float w = bitmap.getWidth() * scale, h = bitmap.getHeight() * scale;
        destination.set(b.exactCenterX() - w / 2, b.exactCenterY() - h / 2, b.exactCenterX() + w / 2, b.exactCenterY() + h / 2);
        image.setAlpha(alpha);
        canvas.drawBitmap(bitmap, null, destination, image);
    }
    @Override public void setAlpha(int alpha) { this.alpha = Math.max(0, Math.min(255, alpha)); invalidateSelf(); }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(@Nullable ColorFilter filter) { image.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return alpha == 255 ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT; }
}
