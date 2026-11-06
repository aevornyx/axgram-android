package one.axgram.core.appearance;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Original approved mark. The interrupted left side and projecting blue bar are intentional. */
public final class AxBrandDrawable extends Drawable {
    private static final float[][] POINTS={
        {47,10,55,10,94,90,77,90,50,35,39,56,22,56},
        {16,72,33,72,24,90,6,90},
        {15,57,54,57,61,71,9,71}
    };
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path[] paths=new Path[3];
    private final LinearGradient[] gradients=new LinearGradient[3];
    private int alpha=255;
    private boolean dark;
    private float elapsed=1400;
    public AxBrandDrawable(boolean dark) {
        for(int i=0;i<paths.length;i++) {
            paths[i]=new Path();paths[i].moveTo(POINTS[i][0],POINTS[i][1]);
            for(int j=2;j<POINTS[i].length;j+=2)paths[i].lineTo(POINTS[i][j],POINTS[i][j+1]);
            paths[i].close();
        }
        setDark(dark);
    }
    public void setDark(boolean dark) {
        this.dark=dark;
        gradients[0]=new LinearGradient(0,0,80,100,dark?0xffe5eff8:0xff586b83,dark?0xff91abc3:0xff24374c,Shader.TileMode.CLAMP);
        gradients[1]=new LinearGradient(10,70,35,100,dark?0xff79b9ff:0xff8ac0ff,dark?0xffa9d2ff:0xffb6d9ff,Shader.TileMode.CLAMP);
        gradients[2]=new LinearGradient(9,57,61,71,new int[]{dark?0xffd9ecff:0xffdfefff,dark?0xff79b9ff:0xff8ac0ff,dark?0xffd9ecff:0xffdfefff},new float[]{0,.24f,.75f},Shader.TileMode.CLAMP);
        invalidateSelf();
    }
    public void setElapsed(float milliseconds) { elapsed=milliseconds;invalidateSelf(); }
    public static float progress(float time,float start,float duration) { return Math.max(0,Math.min(1,(time-start)/duration)); }
    public static float ease(float value) { float t=1-value;return 1-t*t*t; }
    @Override public void draw(Canvas canvas) {
        Rect b=getBounds();int save=canvas.save();canvas.translate(b.left,b.top);canvas.scale(b.width()/100f,b.height()/100f);
        for(int i=0;i<3;i++) {
            float p=progress(elapsed,i==0?100:i==1?160:280,i==2?460:540);
            float q=ease(p),remaining=1-q;
            int piece=canvas.save();
            canvas.translate((i==0?-100:i==1?105:-130)*remaining,(i==0?110:i==1?-115:14)*remaining);
            canvas.rotate((i==0?-62:i==1?60:0)*remaining,50,50);
            canvas.scale(i==2?1-.7f*remaining:1-.65f*remaining,i==2?1:1-.65f*remaining,50,50);
            paint.setAlpha(Math.round(alpha*q));paint.setShader(gradients[i]);canvas.drawPath(paths[i],paint);canvas.restoreToCount(piece);
        }
        canvas.restoreToCount(save);
    }
    @Override public void setAlpha(int alpha) { this.alpha=alpha;invalidateSelf(); }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter);invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public ConstantState getConstantState() {
        final boolean mode=dark;
        return new ConstantState(){@Override public Drawable newDrawable(){return new AxBrandDrawable(mode);}@Override public int getChangingConfigurations(){return 0;}};
    }
}
