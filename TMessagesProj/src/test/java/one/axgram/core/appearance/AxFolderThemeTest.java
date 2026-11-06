package one.axgram.core.appearance;

import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.ActionBar.Theme;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, shadows = {
        AxAppearanceThemeUpdateTest.PreferencesOnlyController.class,
        AxAppearanceThemeUpdateTest.LocalStrings.class})
public class AxFolderThemeTest {
    @Before public void setup() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        AxAppearance.preferences().edit().clear().commit();
    }

    @Test public void wallpaperVisibilityIsIndependentOfPanelBlurAndOtherEffects() {
        assertTrue(AxAppearance.listWallpaperVisible());
        AxAppearance.preferences().edit().putInt("wallpaper_blur",14).putInt("glass_transparency",34).commit();
        AxAppearance.setListWallpaperVisible(false);
        AxAppearance.setGlassBlur(false);
        assertFalse(AxAppearance.listWallpaperVisible());
        AxAppearance.setGlassBlur(true);
        assertFalse(AxAppearance.listWallpaperVisible());
        AxAppearance.setListWallpaperVisible(true);
        AxAppearance.setGlassBlur(false);
        assertTrue(AxAppearance.listWallpaperVisible());
        assertEquals(14,AxAppearance.wallpaperBlur()); assertEquals(34,AxAppearance.glassTransparency());
    }

    @Test public void quickThemeSwitchKeepsGlassPairAndExistingUserEffects() {
        AxAppearance.preferences().edit().putBoolean("glass_navigation",false).putBoolean("list_wallpaper_visible",false)
                .putInt("wallpaper_blur",14).putInt("glass_transparency",34).commit();
        for (boolean dark : new boolean[]{false,true,false,true}) {
            assertSame(Theme.getTheme(dark ? AxAppearance.DARK : AxAppearance.LIGHT),AxAppearance.prepareQuickThemeSwitch(dark));
            assertEquals(dark ? AxAppearance.MODE_DARK : AxAppearance.MODE_LIGHT,AxAppearance.mode());
            assertFalse(AxAppearance.useGlassNavigation()); assertFalse(AxAppearance.listWallpaperVisible());
            assertEquals(14,AxAppearance.wallpaperBlur()); assertEquals(34,AxAppearance.glassTransparency());
        }
    }

    @Test public void existingBackdropReceivesEveryThemeAnimationColorWithoutReopening() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); View page=new View(context);
        AxChatListBackdrop backdrop=new AxChatListBackdrop(context); page.setBackground(backdrop);
        java.lang.reflect.Field color=AxChatListBackdrop.class.getDeclaredField("paletteColor");color.setAccessible(true);
        org.telegram.ui.ActionBar.ThemeDescription binding=AxChatListBackdrop.colorBinding(page);
        float last=1;
        for (int channel=16;channel<=240;channel+=16) {
            int frame=Color.rgb(channel,channel,channel);binding.setColor(frame,false,false);
            assertSame(backdrop,page.getBackground());assertEquals(frame,color.get(backdrop));
            float fraction=AxChatListBackdrop.darkFraction(frame);assertTrue(fraction<=last);last=fraction;
        }
        assertEquals(1f,AxChatListBackdrop.darkFraction(0xff10161d),0f);
        assertEquals(0f,AxChatListBackdrop.darkFraction(0xfff1f5fa),0f);
    }

    @Test public void compactSegmentsKeepFewFoldersEvenAndDoNotCapFolderCount() {
        for (int count : new int[]{1,2,3,10,50}) {
            int content=AxFolderTabsStyle.minimumContentWidth(1020,count,60);
            assertEquals(Math.round(1020f/Math.min(count,3)*(count>3?.86f:1f)),content+60);
        }
        assertEquals(16,AxFolderTabsStyle.minimumContentWidth(0,20,60));
    }
}
