package one.axgram.core.appearance;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import org.telegram.messenger.R;
import java.util.ArrayList;
import java.util.function.IntFunction;

/** Native, local welcome + appearance selection. Authentication stays in LoginActivity. */
public final class AxWelcomeView extends FrameLayout {
    public interface Listener {
        void onThemeSelected(int mode);
        void onContinue();
        void onLanguageRequested();
    }
    private final Listener listener;
    private final IntFunction<String> strings;
    private final Wallpaper wallpaper;
    private final LinearLayout column,header;
    private final ScrollView scroll;
    private final TextView back,themeToggle;
    private final ArrayList<TextView> textViews=new ArrayList<>(),letters=new ArrayList<>();
    private final ArrayList<Glass> glasses=new ArrayList<>();
    private final Typeface face;
    private FrameLayout emblem;
    private BrandView brand;
    private TextView tagline,primary,language,disclaimer;
    private View[] choices;
    private boolean dark,themeStep;
    private int mode;
    private int safeTop,safeBottom;
    private ValueAnimator entrance;

    public AxWelcomeView(Context context,Listener listener,IntFunction<String> strings,int mode,boolean dark,boolean themeStep) {
        super(context);this.listener=listener;this.strings=strings;this.mode=mode;this.dark=dark;this.themeStep=themeStep;
        Typeface loaded;
        try{loaded=Typeface.createFromAsset(context.getAssets(),"fonts/axgram_onest.ttf");}catch(RuntimeException e){loaded=Typeface.create("sans-serif",Typeface.NORMAL);}
        face=loaded;setClipChildren(false);
        wallpaper=new Wallpaper();setBackground(wallpaper);
        header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(20),0,dp(20),0);
        back=text("‹",30);back.setContentDescription(strings.apply(R.string.Back));back.setGravity(Gravity.CENTER);back.setBackground(glass(20));
        header.addView(back,new LinearLayout.LayoutParams(dp(40),dp(40)));back.setOnClickListener(v->showThemeStep(false));
        View gap=new View(context);header.addView(gap,new LinearLayout.LayoutParams(0,1,1));
        themeToggle=text("◐",23);themeToggle.setContentDescription(strings.apply(R.string.AxWelcomeSwitchTheme));themeToggle.setGravity(Gravity.CENTER);themeToggle.setBackground(glass(20));
        header.addView(themeToggle,new LinearLayout.LayoutParams(dp(40),dp(40)));themeToggle.setOnClickListener(v->listener.onThemeSelected(this.dark?0:1));
        addView(header,new LayoutParams(-1,dp(56),Gravity.TOP));
        scroll=new ScrollView(context);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        column=new LinearLayout(context);column.setOrientation(LinearLayout.VERTICAL);column.setPadding(dp(25),0,dp(25),dp(22));
        scroll.addView(column,new ScrollView.LayoutParams(-1,-2));addView(scroll,new LayoutParams(-1,-1));
        ViewCompat.setOnApplyWindowInsetsListener(this,(v,insets)->{androidx.core.graphics.Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());setSafeInsets(bars.top,bars.bottom);return insets;});
        int status=getResources().getIdentifier("status_bar_height","dimen","android");
        setSafeInsets(status==0?0:getResources().getDimensionPixelSize(status),0);
        render();
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private int ink(){return dark?0xffeaf1f8:0xff152233;}
    private int muted(){return dark?0xffa6b5c4:0xff596b7e;}
    private int accent(){return dark?0xff9fcede:0xff326a94;}
    private TextView text(String value,int size){TextView v=new TextView(getContext());v.setText(value);v.setTextSize(TypedValue.COMPLEX_UNIT_SP,size);v.setTypeface(face);v.setTextColor(ink());v.setIncludeFontPadding(false);textViews.add(v);return v;}
    private TextView local(int id,int size){return text(strings.apply(id),size);}
    private Glass glass(int radius){Glass g=new Glass(radius);glasses.add(g);return g;}
    private LinearLayout.LayoutParams row(int height,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,height<0?height:dp(height));p.topMargin=dp(top);return p;}
    public void setSafeInsets(int top,int bottom){safeTop=top;safeBottom=bottom;LayoutParams h=(LayoutParams)header.getLayoutParams();h.topMargin=top;header.setLayoutParams(h);LayoutParams s=(LayoutParams)scroll.getLayoutParams();s.topMargin=top+dp(56);s.bottomMargin=bottom;scroll.setLayoutParams(s);requestLayout();}
    public boolean isThemeStep(){return themeStep;}
    public void showThemeStep(boolean show){if(themeStep==show)return;stopEntrance();themeStep=show;render();scroll.scrollTo(0,0);if(!show)playEntrance();}
    public void refresh(int mode,boolean dark){stopEntrance();this.mode=mode;this.dark=dark;render();}
    /** Theme delegates run for every animation frame. Do not rebuild the controls each time. */
    public void updateAppearance(int mode,boolean dark){if(this.mode!=mode||this.dark!=dark)refresh(mode,dark);}
    private void render(){
        column.removeAllViews();letters.clear();textViews.clear();glasses.clear();textViews.add(back);textViews.add(themeToggle);
        back.setVisibility(themeStep?VISIBLE:INVISIBLE);back.setTextColor(ink());themeToggle.setTextColor(ink());
        back.setBackground(glass(20));themeToggle.setBackground(glass(20));
        primary=null;language=null;disclaimer=null;tagline=null;emblem=null;brand=null;
        if(themeStep)renderThemes();else renderWelcome();wallpaper.invalidateSelf();invalidate();
    }
    private void renderWelcome(){
        View top=new View(getContext());column.addView(top,row(104,0));
        emblem=new FrameLayout(getContext());emblem.setBackground(glass(32));emblem.setClipChildren(false);
        brand=new BrandView(getContext());LayoutParams mark=new LayoutParams(dp(84),dp(84),Gravity.CENTER);emblem.addView(brand,mark);
        emblem.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);emblem.setContentDescription("AxGram");brand.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams logo=new LinearLayout.LayoutParams(dp(132),dp(132));logo.gravity=Gravity.CENTER_HORIZONTAL;column.addView(emblem,logo);
        LinearLayout name=new LinearLayout(getContext());name.setLayoutDirection(LAYOUT_DIRECTION_LTR);name.setGravity(Gravity.CENTER);name.setContentDescription("AxGram");name.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        for(char ch:"AxGram".toCharArray()){TextView letter=text(String.valueOf(ch),47);letter.setLetterSpacing(-.05f);letter.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);letters.add(letter);name.addView(letter,new LinearLayout.LayoutParams(-2,-2));}
        column.addView(name,row(-2,24));
        tagline=local(R.string.AxWelcomeTagline,17);tagline.setTextColor(muted());tagline.setGravity(Gravity.CENTER);tagline.setLineSpacing(dp(3),1);column.addView(tagline,row(-2,9));
        View spacer=new View(getContext());column.addView(spacer,new LinearLayout.LayoutParams(1,dp(26),1));
        primary=button(R.string.AxWelcomeStart);primary.setOnClickListener(v->showThemeStep(true));column.addView(primary,row(56,24));
        language=local(R.string.Language,13);language.setTextColor(accent());language.setGravity(Gravity.CENTER);language.setMinHeight(dp(44));language.setOnClickListener(v->listener.onLanguageRequested());column.addView(language,row(-2,4));
        disclaimer=local(R.string.AxWelcomeUnofficial,11);disclaimer.setGravity(Gravity.CENTER);disclaimer.setTextColor(muted());column.addView(disclaimer,row(-2,16));
    }
    private TextView button(int resource){TextView button=local(resource,16);button.setGravity(Gravity.CENTER);button.setMinHeight(dp(56));button.setPadding(dp(18),dp(12),dp(18),dp(12));button.setBackground(glass(25));button.setFocusable(true);return button;}
    private void renderThemes(){
        TextView title=local(R.string.AxWelcomeThemeTitle,30);column.addView(title,row(-2,28));
        TextView copy=local(R.string.AxWelcomeThemeCopy,14);copy.setTextColor(muted());copy.setLineSpacing(dp(3),1);column.addView(copy,row(-2,10));
        choices=new View[3];int[] labels={R.string.AxAppearanceLight,R.string.AxAppearanceDark,R.string.AxAppearanceSystem};
        int[] descriptions={R.string.AxWelcomeLightCopy,R.string.AxWelcomeDarkCopy,R.string.AxWelcomeSystemCopy};
        for(int i=0;i<3;i++){
            final int choice=i;LinearLayout v=new LinearLayout(getContext());v.setGravity(Gravity.CENTER_VERTICAL);v.setPadding(dp(15),dp(14),dp(15),dp(14));v.setMinimumHeight(dp(88));
            v.addView(new Preview(getContext(),i),new LinearLayout.LayoutParams(dp(41),dp(54)));
            LinearLayout words=new LinearLayout(getContext());words.setOrientation(LinearLayout.VERTICAL);TextView label=local(labels[i],15),description=local(descriptions[i],12);description.setTextColor(muted());words.addView(label,new LinearLayout.LayoutParams(-1,-2));LinearLayout.LayoutParams detail=new LinearLayout.LayoutParams(-1,-2);detail.topMargin=dp(3);words.addView(description,detail);
            LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1);wp.leftMargin=dp(14);wp.rightMargin=dp(9);v.addView(words,wp);
            TextView check=text(mode==i?"✓":"",19);check.setTextColor(accent());v.addView(check,new LinearLayout.LayoutParams(dp(20),-2));
            Glass g=glass(22);g.selected=mode==i;v.setBackground(g);v.setFocusable(true);v.setSelected(mode==i);v.setContentDescription(strings.apply(labels[i]));v.setOnClickListener(w->listener.onThemeSelected(choice));choices[i]=v;column.addView(v,row(-2,i==0?32:12));
        }
        View spacer=new View(getContext());column.addView(spacer,new LinearLayout.LayoutParams(1,dp(26),1));
        primary=button(R.string.Continue);primary.setOnClickListener(v->listener.onContinue());column.addView(primary,row(56,24));
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);column.setMinimumHeight(Math.max(0,h-safeTop-safeBottom-dp(56)));}
    public void playEntrance(){
        if(themeStep)return;stopEntrance();
        if(!motionEnabled()){applyEntrance(1400);return;}
        entrance=ValueAnimator.ofFloat(0,1400);entrance.setDuration(1400);entrance.setInterpolator(input->input);entrance.addUpdateListener(a->applyEntrance((float)a.getAnimatedValue()));entrance.start();
    }
    private boolean motionEnabled(){if(Build.VERSION.SDK_INT>=26)return ValueAnimator.areAnimatorsEnabled();return Settings.Global.getFloat(getContext().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1)>0;}
    private float channel(float time,float start,float duration){return AxBrandDrawable.ease(AxBrandDrawable.progress(time,start,duration));}
    private void rise(View v,float progress,float from){if(v!=null){v.setAlpha(progress);v.setTranslationY(dp(from)*(1-progress));}}
    private void applyEntrance(float time){
        if(emblem==null)return;
        float tile=channel(time,40,380);emblem.setAlpha(tile);emblem.setScaleX(.62f+.38f*tile);emblem.setScaleY(.62f+.38f*tile);emblem.setRotation(-7*(1-tile));emblem.setTranslationY(dp(12)*(1-tile));
        float lock=AxBrandDrawable.progress(time,680,280);float bump=(float)Math.sin(lock*Math.PI)*.035f;emblem.setScaleX(emblem.getScaleX()+bump);emblem.setScaleY(emblem.getScaleY()+bump);
        brand.mark.setElapsed(time);brand.invalidate();
        if(emblem.getBackground() instanceof Glass){Glass g=(Glass)emblem.getBackground();g.shine=AxBrandDrawable.progress(time,700,420);g.invalidateSelf();}
        for(int i=0;i<letters.size();i++)rise(letters.get(i),channel(time,700+i*45,320),16);
        rise(tagline,channel(time,960,320),13);rise(primary,channel(time,920,380),24);rise(language,channel(time,1040,280),13);rise(disclaimer,channel(time,1100,240),13);rise(themeToggle,channel(time,850,320),-10);
        if(primary.getBackground() instanceof Glass){Glass g=(Glass)primary.getBackground();g.shine=AxBrandDrawable.progress(time,1120,280);g.invalidateSelf();}
        wallpaper.motion=channel(time,0,1400);wallpaper.invalidateSelf();
    }
    public void stopEntrance(){if(entrance!=null){entrance.cancel();entrance=null;}applyEntrance(1400);}
    @Override protected void onDetachedFromWindow(){stopEntrance();super.onDetachedFromWindow();}
    private final class BrandView extends View {
        final AxBrandDrawable mark=new AxBrandDrawable(dark);
        BrandView(Context c){super(c);mark.setCallback(this);}
        @Override protected boolean verifyDrawable(Drawable d){return d==mark||super.verifyDrawable(d);}
        @Override protected void onDraw(Canvas canvas){mark.setBounds(0,0,getWidth(),getHeight());mark.draw(canvas);}
    }
    private final class Glass extends Drawable {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);final RectF rect=new RectF();final int radius;boolean selected;float shine=1;
        Glass(int radius){this.radius=radius;}
        @Override public void draw(Canvas canvas){
            rect.set(getBounds());float r=dp(radius);
            paint.setStyle(Paint.Style.FILL);paint.setAlpha(255);paint.setShader(new LinearGradient(rect.left,rect.top,rect.right,rect.bottom,dark?0xe6263544:0xeaf9fcff,dark?0xe3223c4c:0xead7e8f7,Shader.TileMode.CLAMP));canvas.drawRoundRect(rect,r,r,paint);
            paint.setShader(null);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(selected?1.5f:1));paint.setColor(selected?accent():dark?0x68d4e7f7:0xf0ffffff);rect.inset(dp(.5f),dp(.5f));canvas.drawRoundRect(rect,r,r,paint);paint.setStyle(Paint.Style.FILL);
            if(shine>0&&shine<1){int save=canvas.save();android.graphics.Path clip=new android.graphics.Path();clip.addRoundRect(rect,r,r,android.graphics.Path.Direction.CW);canvas.clipPath(clip);float x=rect.left+rect.width()*(-.6f+2.1f*shine);paint.setShader(new LinearGradient(x,0,x+rect.width()*.45f,0,new int[]{0,0x99ffffff,0},null,Shader.TileMode.CLAMP));canvas.drawRect(rect,paint);paint.setShader(null);canvas.restoreToCount(save);}
        }
        @Override public void setAlpha(int a){}@Override public void setColorFilter(ColorFilter f){}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
    private final class Wallpaper extends Drawable {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);Bitmap light,night;float motion=1;
        Bitmap bitmap(boolean nightMode){Bitmap b=nightMode?night:light;if(b==null){try(java.io.InputStream stream=getContext().getAssets().open("axgram/list-"+(nightMode?"dark":"light")+".png")){BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=2;b=BitmapFactory.decodeStream(stream,null,options);if(nightMode)night=b;else light=b;}catch(java.io.IOException ignored){}}return b;}
        @Override public void draw(Canvas canvas){canvas.drawColor(dark?0xff0c131b:0xffe9eef5);Bitmap b=bitmap(dark);if(b==null)return;float w=getBounds().width(),h=getBounds().height();float scale=Math.max(w/b.getWidth(),h/b.getHeight())*(1+.035f*(1-motion));float bw=b.getWidth()*scale,bh=b.getHeight()*scale;paint.setAlpha(themeStep?95:255);canvas.drawBitmap(b,null,new RectF((w-bw)/2,(h-bh)/2,(w+bw)/2,(h+bh)/2),paint);paint.setAlpha(255);}
        @Override public void setAlpha(int a){}@Override public void setColorFilter(ColorFilter f){}@Override public int getOpacity(){return PixelFormat.OPAQUE;}
    }
    private final class Preview extends View {
        final int choice;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        Preview(Context c,int choice){super(c);this.choice=choice;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c){
            float w=getWidth(),h=getHeight();int save=c.save();android.graphics.Path clip=new android.graphics.Path();clip.addRoundRect(new RectF(0,0,w,h),dp(9),dp(9),android.graphics.Path.Direction.CW);c.clipPath(clip);
            if(choice==2){paint.setColor(0xffe9eef5);c.drawRect(0,0,w/2,h,paint);paint.setColor(0xff18242f);c.drawRect(w/2,0,w,h,paint);}else{Bitmap b=wallpaper.bitmap(choice==1);if(b!=null)c.drawBitmap(b,null,new RectF(0,0,w,h),paint);}
            paint.setColor(choice==1?0xff405265:0xeef5f9ff);c.drawRoundRect(new RectF(dp(5),dp(9),w-dp(5),dp(24)),dp(4),dp(4),paint);
            paint.setColor(choice==1?0xff74abc4:0xff96c7e9);c.drawRoundRect(new RectF(dp(12),dp(28),w-dp(5),h-dp(10)),dp(4),dp(4),paint);c.restoreToCount(save);
        }
    }
}
