package one.axgram.core.appearance;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.telegram.messenger.R;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AxWelcomeNativeTest {
    private Context context(){return RuntimeEnvironment.getApplication();}
    private View find(View view,String text){
        if(text.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof TextView && text.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),text);if(found!=null)return found;}}return null;
    }
    private void layout(View view,int width,int height){for(int i=0;i<2;i++){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);}}
    private void screenshot(View view,String name)throws Exception{
        Bitmap b=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(b));
        java.io.File folder=new java.io.File("D:/AxGram-analysis/first-run-native-2026-10-30/native-renders");folder.mkdirs();
        try(java.io.FileOutputStream stream=new java.io.FileOutputStream(new java.io.File(folder,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,stream);}
    }
    @Test public void welcomeAppearanceAndContinueUseNativeControlsWithoutAnAuthenticationRequest()throws Exception {
        final int[] events={0,0,0};final AxWelcomeView[] holder=new AxWelcomeView[1];
        holder[0]=new AxWelcomeView(context(),new AxWelcomeView.Listener(){
            public void onThemeSelected(int mode){events[0]++;holder[0].refresh(mode,mode==1);}
            public void onContinue(){events[1]++;}
            public void onLanguageRequested(){events[2]++;}
        },id->context().getString(id),0,false,false);
        AxWelcomeView view=holder[0];layout(view,370,800);screenshot(view,"welcome-light");
        assertFalse(view.isThemeStep());find(view,context().getString(R.string.Language)).performClick();assertEquals(1,events[2]);
        view.playEntrance();find(view,context().getString(R.string.AxWelcomeStart)).performClick();assertTrue(view.isThemeStep());assertEquals(0,events[1]);
        find(view,context().getString(R.string.AxAppearanceDark)).performClick();assertEquals(1,events[0]);assertTrue(view.isThemeStep());layout(view,370,800);screenshot(view,"themes-dark");
        find(view,context().getString(R.string.Continue)).performClick();assertEquals(1,events[1]);
        view.showThemeStep(false);layout(view,370,800);screenshot(view,"welcome-dark");assertFalse(view.isThemeStep());
        view.refresh(2,true);assertFalse(view.isThemeStep());layout(view,320,640);screenshot(view,"welcome-narrow");
    }
    @Test public void originalMarkHasASeparateBlueFootAndTransparentGapAndNoText()throws Exception {
        AxBrandDrawable mark=new AxBrandDrawable(false);mark.setBounds(0,0,200,200);Bitmap b=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);mark.draw(new Canvas(b));
        int foot=b.getPixel(42,164);assertTrue(Color.alpha(foot)>240);assertTrue(Color.blue(foot)>Color.red(foot));
        assertEquals(0,Color.alpha(b.getPixel(76,160)));assertTrue(Color.alpha(b.getPixel(166,170))>240);
        mark.setElapsed(0);Bitmap start=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);mark.draw(new Canvas(start));assertEquals(0,Color.alpha(start.getPixel(42,164)));
        mark.setElapsed(1400);assertNotNull(mark.getConstantState().newDrawable());
    }
    @Test public void repeatedThemeTogglesSurviveAnimationDelegatesAndKeepControlsAttached() {
        final AxWelcomeView[] holder=new AxWelcomeView[1];final int[] clicks={0,-1};
        holder[0]=new AxWelcomeView(context(),new AxWelcomeView.Listener(){
            public void onThemeSelected(int mode){clicks[0]++;clicks[1]=mode;holder[0].updateAppearance(mode,mode==1);}
            public void onContinue(){}public void onLanguageRequested(){}
        },context()::getString,0,false,false);
        AxWelcomeView view=holder[0];layout(view,370,800);
        String label=context().getString(R.string.AxWelcomeSwitchTheme);
        for(int i=0;i<8;i++){
            assertTrue(find(view,label).performClick());
            int mode=i%2==0?1:0;
            assertEquals(mode,clicks[1]);
            View start=find(view,context().getString(R.string.AxWelcomeStart));
            for(int frame=0;frame<60;frame++)view.updateAppearance(mode,mode==1);
            assertSame(start,find(view,context().getString(R.string.AxWelcomeStart)));
            assertTrue(find(view,label).isClickable());
        }
        assertEquals(8,clicks[0]);
        view.showThemeStep(true);
        for(int i=0;i<4;i++)assertTrue(find(view,label).performClick());
        assertTrue(view.isThemeStep());assertEquals(12,clicks[0]);
    }
    @Test @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    public void returningFromAppearanceReplaysEntranceAndLeavesStartUsable() throws Exception {
        java.lang.reflect.Method durationScale=android.animation.ValueAnimator.class.getDeclaredMethod("setDurationScale",float.class);
        java.lang.reflect.Method getScale=android.animation.ValueAnimator.class.getDeclaredMethod("getDurationScale");
        durationScale.setAccessible(true);getScale.setAccessible(true);
        float originalScale=(Float)getScale.invoke(null);
        try {
            durationScale.invoke(null,1f);
            AxWelcomeView view=new AxWelcomeView(context(),new AxWelcomeView.Listener(){public void onThemeSelected(int mode){}public void onContinue(){}public void onLanguageRequested(){}},context()::getString,0,false,true);
            layout(view,370,800);view.showThemeStep(false);layout(view,370,800);
            java.lang.reflect.Field field=AxWelcomeView.class.getDeclaredField("entrance");field.setAccessible(true);
            android.animation.ValueAnimator animation=(android.animation.ValueAnimator)field.get(view);
            assertNotNull("Returning to welcome must start its entrance",animation);
            assertTrue(animation.isStarted());
            animation.setCurrentPlayTime(60);
            for(int i=0;i<3;i++)view.updateAppearance(0,false);
            assertSame("Repeated resume/theme delegates must retain the running entrance",animation,field.get(view));
            assertTrue(animation.isStarted());
            View mark=find(view,"AxGram");assertNotNull(mark);assertTrue(mark.getScaleX()<.95f);
            animation.end();assertEquals(1f,mark.getScaleX(),.001f);
            assertTrue(find(view,context().getString(R.string.AxWelcomeStart)).performClick());assertTrue(view.isThemeStep());
        } finally {durationScale.invoke(null,originalScale);}
    }

    @Test @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    public void firstFullVisibilityStartsEntranceAndRepeatedResumeKeepsIt() throws Exception {
        org.telegram.messenger.ApplicationLoader.applicationContext=context();
        int mode=AxAppearance.mode();
        AxWelcomeView view=new AxWelcomeView(context(),new AxWelcomeView.Listener(){public void onThemeSelected(int value){}public void onContinue(){}public void onLanguageRequested(){}},context()::getString,mode,AxAppearance.isModeDark(mode),false);
        org.robolectric.android.controller.ActivityController<android.app.Activity> activity=org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().visible();
        try {
            activity.get().setContentView(view);layout(view,370,800);
            org.telegram.ui.IntroActivity intro=new org.telegram.ui.IntroActivity();
            java.lang.reflect.Field welcome=org.telegram.ui.IntroActivity.class.getDeclaredField("welcome");welcome.setAccessible(true);welcome.set(intro,view);
            java.lang.reflect.Field played=org.telegram.ui.IntroActivity.class.getDeclaredField("played");played.setAccessible(true);
            java.lang.reflect.Field entrance=AxWelcomeView.class.getDeclaredField("entrance");entrance.setAccessible(true);
            intro.onResume();assertFalse(played.getBoolean(intro));assertNull(entrance.get(view));
            intro.onBecomeFullyVisible();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100));
            assertTrue("First visible frame must schedule the entrance",played.getBoolean(intro));
            Object animation=entrance.get(view);assertNotNull(animation);View mark=find(view,"AxGram");
            intro.onResume();intro.onResume();assertSame(animation,entrance.get(view));assertSame(mark,find(view,"AxGram"));
            intro.onPause();
        } finally {activity.pause().stop().destroy();}
    }
    @Test public void russianCopyAndLargeTextRemainScrollableWithoutLosingTheContinueControl()throws Exception {
        android.content.res.Configuration config=new android.content.res.Configuration(context().getResources().getConfiguration());config.setLocale(new java.util.Locale("ru"));config.fontScale=1.3f;
        Context ru=context().createConfigurationContext(config);
        AxWelcomeView view=new AxWelcomeView(ru,new AxWelcomeView.Listener(){public void onThemeSelected(int mode){}public void onContinue(){}public void onLanguageRequested(){}},ru::getString,0,false,false);
        layout(view,370,800);assertNotNull(find(view,"Начать"));screenshot(view,"welcome-ru-large-text");
        view.showThemeStep(true);layout(view,320,640);assertNotNull(find(view,ru.getString(R.string.Continue)));screenshot(view,"themes-ru-narrow-large-text");
        final boolean[] scrollable={false};
        class Scan {void walk(View v){if(v instanceof android.widget.ScrollView){android.widget.ScrollView s=(android.widget.ScrollView)v;scrollable[0]=s.getChildAt(0).getHeight()>=s.getHeight();}if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)walk(g.getChildAt(i));}}}
        new Scan().walk(view);assertTrue(scrollable[0]);
    }
}
