package one.axgram.core.appearance;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.widget.ImageView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, shadows = AxIconThemeShadow.class)
public class AxIconPackTest {
    @Before public void setup() {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        AxAppearance.preferences().edit().clear().commit();
    }
    @Test public void originalAndUnknownResourcesSurviveTheReverseSwitch() {
        assertTrue(AxIconPack.enabled()); assertNotEquals(R.drawable.msg_settings,AxIconPack.resolve(R.drawable.msg_settings));
        assertEquals(0,AxIconPack.resolve(0));assertEquals(Integer.MAX_VALUE,AxIconPack.resolve(Integer.MAX_VALUE));
        AxIconPack.setEnabled(false);
        assertEquals(R.drawable.msg_settings,AxIconPack.resolve(R.drawable.msg_settings));
        assertEquals(R.drawable.settings_privacy,AxIconPack.resolve(R.drawable.settings_privacy));
        assertFalse(AxAppearance.preferences().getBoolean("modern_icons",true));
    }
    static class Recording extends ColorDrawable {
        int draws;
        Recording() { super(0xffffffff); }
        @Override public void draw(Canvas canvas) { draws++;super.draw(canvas); }
    }
    @Test public void sameAttachedDrawableChangesFamilyAndPreservesGeometryAlphaAndTint() {
        Recording stock=new Recording(),modern=new Recording();
        AxIconPack.IconDrawable icon=new AxIconPack.IconDrawable(stock,modern);
        ImageView view=new ImageView(RuntimeEnvironment.getApplication());view.setImageDrawable(icon);
        Rect bounds=new Rect(2,3,26,27);icon.setBounds(bounds);icon.setAlpha(93);
        PorterDuffColorFilter tint=new PorterDuffColorFilter(0xff85ccd9,PorterDuff.Mode.SRC_IN);icon.setColorFilter(tint);
        Canvas canvas=new Canvas(Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888));
        icon.draw(canvas); assertEquals(1,modern.draws);assertEquals(0,stock.draws);
        AxIconPack.setEnabled(false);icon.draw(canvas);
        assertEquals(1,stock.draws);assertSame(icon,view.getDrawable());
        assertEquals(bounds,stock.getBounds());assertEquals(bounds,modern.getBounds());
        ColorDrawable nativeAlpha=new ColorDrawable(0xffffffff);nativeAlpha.setAlpha(93);
        assertEquals(nativeAlpha.getAlpha(),stock.getAlpha());assertEquals(stock.getAlpha(),modern.getAlpha());
        assertSame(tint,stock.getColorFilter());assertSame(tint,modern.getColorFilter());
        AxIconPack.setEnabled(true);icon.draw(canvas);assertEquals(2,modern.draws);
    }
    @Test public void replacingAnIconWithACustomDrawableIsNotUndoneByThePreference() {
        ImageView view=new ImageView(RuntimeEnvironment.getApplication());
        AxIconPack.bind(view,R.drawable.msg_settings);
        ColorDrawable custom=new ColorDrawable(0xff123456);view.setImageDrawable(custom);
        AxIconPack.setEnabled(false);assertSame(custom,view.getDrawable());
        AxIconPack.setEnabled(true);assertSame(custom,view.getDrawable());
    }
    @Test public void accountEditorAndProfileActionsUseThePackAndRestoreStock() {
        int[] originals={R.drawable.settings_stars,R.drawable.settings_gram_24,R.drawable.settings_calls,R.drawable.filled_chatlist_mention,
            R.drawable.filled_birthday,R.drawable.filled_profile_edit_24,
            R.drawable.outline_profile_settings,R.drawable.outline_add_account};
        Canvas canvas=new Canvas(Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888));
        for (int resource:originals) {
            assertTrue(AxIconPack.hasIcon(resource));
            assertNotEquals(resource,AxIconPack.resolve(resource));
            AxIconPack.IconDrawable badge=(AxIconPack.IconDrawable)AxIconPack.badge(RuntimeEnvironment.getApplication(),resource);
            assertTrue(badge.modern instanceof AxGlassIcon);
            badge.setBounds(2,3,34,35);badge.setAlpha(77);badge.draw(canvas);
            assertEquals(77,badge.modern.getAlpha());
            assertNotNull(badge.getConstantState().newDrawable());
            AxIconPack.setEnabled(false);assertEquals(resource,AxIconPack.resolve(resource));badge.draw(canvas);
            AxIconPack.setEnabled(true);
        }
    }
    @Test public void everyRegisteredIconInflatesDrawsAndRetainsACloneableNativeFallback() throws Exception {
        java.lang.reflect.Field field=AxIconPack.class.getDeclaredField("icons");field.setAccessible(true);
        android.util.SparseIntArray map=(android.util.SparseIntArray)field.get(null);
        assertTrue(map.size()>=100);
        Canvas canvas=new Canvas(Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888));
        for(int i=0;i<map.size();i++) {
            android.graphics.drawable.Drawable icon=AxIconPack.drawable(RuntimeEnvironment.getApplication(),map.keyAt(i));
            assertNotNull(icon);assertNotNull(icon.getConstantState());
            icon.setBounds(0,0,48,48);icon.draw(canvas);
            AxIconPack.setEnabled(false);icon.draw(canvas);AxIconPack.setEnabled(true);
            assertNotSame(icon,icon.getConstantState().newDrawable(RuntimeEnvironment.getApplication().getResources()));
        }
    }
}
