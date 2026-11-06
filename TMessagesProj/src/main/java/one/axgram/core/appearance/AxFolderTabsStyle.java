package one.axgram.core.appearance;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import androidx.core.graphics.ColorUtils;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/** The approved segmented glass folder bar. Geometry remains tied to native folder IDs/scrolling. */
public final class AxFolderTabsStyle {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int previousTop, previousBottom;
    private float previousY, previousHeight;
    private boolean gradientReady;

    public static int minimumContentWidth(int width, int count, int padding) {
        return count <= 0 ? 16 : Math.max(16, Math.round(width / (float) Math.min(count, 3) * (count > 3 ? .86f : 1f)) - padding);
    }

    public void panel(Canvas canvas, float left, float top, float right, float bottom) {
        panel.setColor(panelColor(null));
        rect.set(left, top, right, bottom);
        canvas.drawRoundRect(rect, AndroidUtilities.dpf2(18), AndroidUtilities.dpf2(18), panel);
        rim(canvas, Theme.isCurrentThemeDark() ? 32 : 180, 18);
    }

    public static int panelColor(Theme.ResourcesProvider resources) {
        return AxAppearance.glassColor(ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_windowBackgroundWhite, resources), 210));
    }

    public void selected(Canvas canvas, RectF bounds) {
        boolean dark = Theme.isCurrentThemeDark();
        int base = Theme.getColor(Theme.key_windowBackgroundWhite), accent = AxChatListStyle.accent();
        int lower = AxChatListStyle.selectedFill(base, accent, dark);
        int upper = ColorUtils.blendARGB(lower, Color.WHITE, dark ? .045f : .38f);
        if (!gradientReady || upper != previousTop || lower != previousBottom || bounds.top != previousY || bounds.height() != previousHeight) {
            fill.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom, upper, lower, Shader.TileMode.CLAMP));
            previousTop = upper; previousBottom = lower; previousY = bounds.top; previousHeight = bounds.height(); gradientReady = true;
        }
        rect.set(bounds);
        canvas.drawRoundRect(rect, AndroidUtilities.dpf2(15), AndroidUtilities.dpf2(15), fill);
        rim(canvas, dark ? 35 : 195, 15);
    }

    private void rim(Canvas canvas, int alpha, int radius) {
        line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(Math.max(1, AndroidUtilities.dpf2(.6f)));
        line.setColor(ColorUtils.setAlphaComponent(Color.WHITE, alpha));
        rect.inset(line.getStrokeWidth() / 2, line.getStrokeWidth() / 2);
        canvas.drawRoundRect(rect, AndroidUtilities.dpf2(radius), AndroidUtilities.dpf2(radius), line);
    }

    public void separator(Canvas canvas, float x, float centerY) {
        line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(Math.max(1, AndroidUtilities.dpf2(.5f)));
        line.setColor(ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 24));
        canvas.drawLine(x, centerY - AndroidUtilities.dpf2(7), x, centerY + AndroidUtilities.dpf2(7), line);
    }
}
