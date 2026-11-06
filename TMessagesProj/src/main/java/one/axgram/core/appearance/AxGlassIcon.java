package one.axgram.core.appearance;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import androidx.core.graphics.ColorUtils;
import org.telegram.ui.ActionBar.Theme;

/** Small native glass emblem: two offset layers, a restrained rim and a readable glyph. */
final class AxGlassIcon extends Drawable {
    private final Drawable glyph;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient back, front, rim;
    private int previousAccent, previousSurface, alpha = 255;

    AxGlassIcon(Drawable glyph) { this.glyph = glyph; }

    @Override public void draw(Canvas canvas) {
        int surface=Theme.getColor(Theme.key_windowBackgroundWhite);
        int accent=AxAppearance.accentColor();
        if (accent==0) accent=Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
        if (back==null || previousAccent!=accent || previousSurface!=surface) {
            previousAccent=accent; previousSurface=surface;
            boolean dark=ColorUtils.calculateLuminance(surface)<0.4;
            int pale=ColorUtils.blendARGB(accent,0xffffffff,dark ? .36f : .62f);
            int deep=ColorUtils.blendARGB(accent,0xff142537,.16f);
            back=new LinearGradient(2,2,21,21,pale,deep,Shader.TileMode.CLAMP);
            front=new LinearGradient(5,5,19,23,
                new int[]{ColorUtils.blendARGB(surface,0xffffffff,dark ? .28f : .62f),
                    ColorUtils.blendARGB(surface,accent,dark ? .48f : .28f),
                    ColorUtils.blendARGB(surface,0xffffffff,dark ? .16f : .80f)},
                new float[]{0,.62f,1},Shader.TileMode.CLAMP);
            rim=new LinearGradient(3,5,23,23,0xd9ffffff,dark ? 0x2274bbd4 : 0x559bbbd0,Shader.TileMode.CLAMP);
        }
        Rect b=getBounds();
        int save=canvas.save();canvas.translate(b.left,b.top);canvas.scale(b.width()/24f,b.height()/24f);
        if (alpha<255) canvas.saveLayerAlpha(0,0,24,24,alpha);
        paint.setStyle(Paint.Style.FILL);paint.setShader(null);paint.setColor(0x16061628);
        canvas.drawRoundRect(4,7,23,23,5,5,paint);
        paint.setAlpha(255);
        int tilt=canvas.save();canvas.rotate(-12,12,12);
        paint.setShader(back);canvas.drawRoundRect(2,1.5f,20,20,5,5,paint);canvas.restoreToCount(tilt);
        paint.setShader(front);canvas.drawRoundRect(3,5,23,22,5,5,paint);
        paint.setShader(rim);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(.65f);
        canvas.drawRoundRect(3.35f,5.35f,22.65f,21.65f,4.7f,4.7f,paint);
        paint.setShader(null);paint.setStyle(Paint.Style.FILL);
        glyph.setBounds(6,7,21,21);
        boolean dark=ColorUtils.calculateLuminance(surface)<0.4;
        glyph.setTint(dark ? 0xffedf8ff : ColorUtils.blendARGB(previousAccent,0xff102b41,.55f));
        glyph.draw(canvas);
        canvas.restoreToCount(save);
    }
    @Override public void setAlpha(int alpha) { this.alpha=alpha;invalidateSelf(); }
    @Override public int getAlpha() { return alpha; }
    // Decorative layers retain their theme palette when callers tint ordinary menu glyphs.
    @Override public void setColorFilter(ColorFilter ignored) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public ConstantState getConstantState() {
        ConstantState state=glyph.getConstantState();
        return state==null ? null : new ConstantState() {
            @Override public Drawable newDrawable() { return new AxGlassIcon(state.newDrawable().mutate()); }
            @Override public int getChangingConfigurations() { return state.getChangingConfigurations(); }
        };
    }
}
