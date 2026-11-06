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

/** Cached, low-cost glass surface: no per-row blur or extra touch target. */
public final class AxChatListSurface {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int lastTop, lastBottom;
    private float lastY, lastHeight;
    private boolean ready;

    public void draw(Canvas canvas, float left, float top, float right, float bottom, float radius, boolean selected) {
        if (right <= left || bottom <= top) return;
        boolean dark = Theme.isCurrentThemeDark();
        int base = Theme.getColor(Theme.key_windowBackgroundWhite);
        int color = selected ? AxChatListStyle.selectedFill(base, AxChatListStyle.accent(), dark) : base;
        boolean blurred = AxAppearance.glassBlurEnabled();
        int topColor = ColorUtils.blendARGB(color, Color.WHITE, dark ? .045f : .35f);
        int bottomColor = color;
        if (!selected && blurred) {
            int material = AxAppearance.glassColor(ColorUtils.setAlphaComponent(base, dark ? 218 : 205));
            topColor = ColorUtils.setAlphaComponent(topColor, Color.alpha(material));
            bottomColor = ColorUtils.setAlphaComponent(bottomColor, Color.alpha(material));
        }
        float h = bottom - top;
        if (!ready || topColor != lastTop || bottomColor != lastBottom || top != lastY || h != lastHeight) {
            fill.setShader(new LinearGradient(0, top, 0, bottom, topColor, bottomColor, Shader.TileMode.CLAMP));
            lastTop = topColor; lastBottom = bottomColor; lastY = top; lastHeight = h; ready = true;
        }
        rect.set(left, top, right, bottom);
        canvas.drawRoundRect(rect, radius, radius, fill);
        drawRim(canvas, radius, selected);
    }

    public void drawEdge(Canvas canvas, float left, float top, float right, float bottom, float radius) {
        if (right <= left || bottom <= top) return;
        rect.set(left, top, right, bottom);
        drawRim(canvas, radius, false);
    }

    private void drawRim(Canvas canvas, float radius, boolean selected) {
        boolean dark = Theme.isCurrentThemeDark();
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1, AndroidUtilities.dpf2(.65f)));
        rim.setColor(ColorUtils.setAlphaComponent(selected ? AxChatListStyle.accent() : (dark ? 0xffb4c5d3 : Color.WHITE), selected ? 50 : dark ? 26 : 185));
        rect.inset(rim.getStrokeWidth() / 2, rim.getStrokeWidth() / 2);
        canvas.drawRoundRect(rect, radius, radius, rim);
    }
}
