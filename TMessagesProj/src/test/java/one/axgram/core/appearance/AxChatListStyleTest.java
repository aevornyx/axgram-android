package one.axgram.core.appearance;

import android.app.Application;
import android.graphics.Typeface;
import android.text.TextPaint;
import androidx.core.graphics.ColorUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.ActionBar.Theme;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, shadows = {
        AxAppearanceThemeUpdateTest.PreferencesOnlyController.class,
        AxAppearanceThemeUpdateTest.LocalStrings.class})
public class AxChatListStyleTest {
    @Before public void setup() { ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication(); }

    @Test public void selectedFolderTextIsReadableForBothThemesAndCustomAccentExtremes() {
        for (boolean dark : new boolean[]{false, true}) {
            int background = dark ? 0xff10161d : 0xfff1f5fa;
            for (int hue = 0; hue < 360; hue += 10) for (float light : new float[]{0, .2f, .5f, .8f, 1}) {
                int accent = ColorUtils.HSLToColor(new float[]{hue, 1, light});
                int selected = AxChatListStyle.selectedFill(background, accent, dark);
                int text = AxChatListStyle.selectedText(background, accent, dark);
                assertTrue(ColorUtils.calculateContrast(text, selected) >= 4.5);
            }
        }
    }

    private static TextPaint paint() { TextPaint p = new TextPaint(); p.setTypeface(Typeface.SERIF); return p; }
    private static TextPaint[] pair() { return new TextPaint[]{paint(), paint()}; }

    @Test public void glassTypographyRestoresOriginalFontsOnLeavingGlassTheme() throws Exception {
        Field active = Theme.class.getDeclaredField("currentTheme"); active.setAccessible(true);
        Object previous = active.get(null);
        Theme.dialogs_namePaint = pair(); Theme.dialogs_nameEncryptedPaint = pair();
        Theme.dialogs_messagePaint = pair(); Theme.dialogs_messagePrintingPaint = pair();
        Theme.dialogs_countTextPaint = paint(); Theme.dialogs_countTextPaint2 = paint();
        Theme.dialogs_timePaint = paint(); Theme.dialogs_timePaintBold = paint();
        Theme.dialogs_timePaintBoldAccent = paint(); Theme.dialogs_messageNamePaint = paint();
        try {
            assertNotNull(Theme.getTheme(AxAppearance.LIGHT));
            active.set(null, Theme.getTheme(AxAppearance.LIGHT));
            AxChatListStyle.applyDialogTypography(true);
            assertNotEquals(Typeface.SERIF, Theme.dialogs_namePaint[0].getTypeface());
            active.set(null, Theme.getTheme("Blue"));
            AxChatListStyle.applyDialogTypography(false);
            assertEquals(Typeface.SERIF, Theme.dialogs_namePaint[0].getTypeface());
            assertEquals(Typeface.SERIF, Theme.dialogs_nameEncryptedPaint[1].getTypeface());
            assertEquals(Typeface.SERIF, Theme.dialogs_messagePaint[1].getTypeface());
            assertEquals(Typeface.SERIF, Theme.dialogs_messagePrintingPaint[0].getTypeface());
            assertEquals(Typeface.SERIF, Theme.dialogs_countTextPaint.getTypeface());
            assertEquals(Typeface.SERIF, Theme.dialogs_timePaintBold.getTypeface());
        } finally { active.set(null, previous); }
    }
}
