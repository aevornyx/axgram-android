package one.axgram.core.appearance;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Quiet native wallpaper for authentication; its veil protects labels and input contrast. */
public final class AxEntryBackgroundDrawable extends Drawable {
    private final Context context;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private boolean dark;private Bitmap light,night;
    public AxEntryBackgroundDrawable(Context context,boolean dark){this.context=context.getApplicationContext();this.dark=dark;}
    public void setDark(boolean dark){this.dark=dark;invalidateSelf();}
    @Override public void draw(Canvas canvas){
        int surface=dark?0xff0c131b:0xffe9eef5;canvas.drawColor(surface);
        Bitmap b=dark?night:light;
        if(b==null){try(java.io.InputStream stream=context.getAssets().open("axgram/list-"+(dark?"dark":"light")+".png")){BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=2;b=BitmapFactory.decodeStream(stream,null,o);if(dark)night=b;else light=b;}catch(java.io.IOException ignored){}}
        if(b!=null){float w=getBounds().width(),h=getBounds().height(),scale=Math.max(w/b.getWidth(),h/b.getHeight());float bw=b.getWidth()*scale,bh=b.getHeight()*scale;paint.setAlpha(255);canvas.drawBitmap(b,null,new RectF((w-bw)/2,(h-bh)/2,(w+bw)/2,(h+bh)/2),paint);}
        paint.setColor((surface&0xffffff)|0xd9000000);canvas.drawRect(getBounds(),paint);
    }
    @Override public void setAlpha(int alpha){}@Override public void setColorFilter(ColorFilter filter){}@Override public int getOpacity(){return PixelFormat.OPAQUE;}
}
