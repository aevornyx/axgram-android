package one.axgram.core.appearance;

import android.graphics.Bitmap;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

/** Applies only to a chat's wallpaper view, never its messages or media. */
public final class AxWallpaperEffects {
    private int applied = -1;
    private int generation;
    private Bitmap requested;
    private int requestedPercent;
    private Drawable blurred;

    public void update(View view, int percent) {
        if (applied == percent) return;
        applied = percent;
        if (Build.VERSION.SDK_INT >= 31) {
            float radius = AndroidUtilities.dpf2(28f * percent / 100f);
            view.setRenderEffect(percent == 0 ? null : RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }
    }

    public Drawable resolve(Drawable source, int percent, Runnable invalidate) {
        if (Build.VERSION.SDK_INT >= 31 || percent == 0 || !(source instanceof BitmapDrawable)) return source;
        Bitmap bitmap = ((BitmapDrawable) source).getBitmap();
        if (bitmap == null || bitmap.isRecycled()) return source;
        if (requested == bitmap && requestedPercent == percent) return blurred == null ? source : blurred;
        requested = bitmap; requestedPercent = percent; blurred = null;
        int ticket = ++generation;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                float scale = Math.min(1f, 480f / Math.max(bitmap.getWidth(), bitmap.getHeight()));
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
                        Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
                Bitmap copy = scaled.copy(Bitmap.Config.ARGB_8888, true);
                Utilities.stackBlurBitmap(copy, Math.max(1, Math.round(20f * percent / 100f)));
                AndroidUtilities.runOnUIThread(() -> {
                    if (ticket != generation) return;
                    blurred = new BitmapDrawable(ApplicationLoader.applicationContext.getResources(), copy);
                    invalidate.run();
                });
            } catch (Throwable error) { FileLog.e(error); }
        });
        return source;
    }
}
