package one.axgram.core.appearance;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35)
public class AxAppearancePreferencesTest {
    private SharedPreferences original, snapshot;
    private static final String[] KEYS = {"theme", "nighttheme", "selectedAutoNightType"};

    @Before public void setup() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        original = ApplicationLoader.applicationContext.getSharedPreferences("appearance-test", Context.MODE_PRIVATE);
        snapshot = AxAppearance.preferences();
        original.edit().clear().commit();
        snapshot.edit().clear().commit();
    }
    private void save() throws Exception {
        Method method = AxAppearance.class.getDeclaredMethod("saveKeys", SharedPreferences.class,
                SharedPreferences.Editor.class, String.class, String[].class);
        method.setAccessible(true);
        SharedPreferences.Editor editor = snapshot.edit();
        method.invoke(null, original, editor, "main_", KEYS);
        editor.commit();
    }
    private void restore() throws Exception {
        Method method = AxAppearance.class.getDeclaredMethod("restoreKeys", SharedPreferences.class, String.class, String[].class);
        method.setAccessible(true);
        method.invoke(null, original, "main_", KEYS);
    }
    @Test public void restoresThemeAndAutoModeWithoutRevertingUnrelatedSettings() throws Exception {
        original.edit().putString("theme", "My custom theme").putString("nighttheme", "Dark Blue")
                .putInt("selectedAutoNightType", 3).putBoolean("notifications", true).commit();
        save();
        original.edit().putString("theme", AxAppearance.LIGHT).putString("nighttheme", AxAppearance.DARK)
                .putInt("selectedAutoNightType", 0).putBoolean("notifications", false).commit();
        restore();
        assertEquals("My custom theme", original.getString("theme", null));
        assertEquals("Dark Blue", original.getString("nighttheme", null));
        assertEquals(3, original.getInt("selectedAutoNightType", -1));
        assertFalse(original.getBoolean("notifications", true));
    }
    @Test public void restoresAbsenceOfDefaultThemeKeys() throws Exception {
        save();
        original.edit().putString("theme", AxAppearance.LIGHT).putInt("selectedAutoNightType", 0).commit();
        restore();
        for (String key : KEYS) assertFalse(original.contains(key));
    }
    @Test public void recapturedDefaultsCannotReviveStaleThemeValues() throws Exception {
        original.edit().putString("theme", "Old theme").commit();
        save();
        original.edit().remove("theme").commit();
        save();
        original.edit().putString("theme", AxAppearance.DARK).commit();
        restore();
        assertFalse(original.contains("theme"));
    }

    @Test public void wallpaperAndGlassSettingsAreIndependentAndBounded() {
        snapshot.edit().putInt("wallpaper_blur",75).putInt("glass_transparency",40).putBoolean("glass_blur",false).commit();
        assertEquals(75,AxAppearance.wallpaperBlur());
        assertEquals(40,AxAppearance.glassTransparency());
        assertFalse(AxAppearance.glassBlurEnabled());
        snapshot.edit().putInt("wallpaper_blur",300).putInt("glass_transparency",-10).commit();
        assertEquals(100,AxAppearance.wallpaperBlur());
        assertEquals(0,AxAppearance.glassTransparency());
        assertFalse(AxAppearance.glassBlurEnabled());
    }
}
