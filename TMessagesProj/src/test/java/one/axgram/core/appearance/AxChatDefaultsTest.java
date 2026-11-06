package one.axgram.core.appearance;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35)
public class AxChatDefaultsTest {
    @Test public void freshChatUsesOwnerTypographyAndRoundness() {
        SharedPreferences p=RuntimeEnvironment.getApplication().getSharedPreferences("fresh-chat",Context.MODE_PRIVATE);
        AxChatDefaults.apply(p);
        assertEquals(16,p.getInt("fons_size",0));assertEquals(17,p.getInt("bubbleRadius",0));
        assertEquals(2,p.getAll().size());
    }
    @Test public void existingPreferencesAreNeverOverwritten() {
        SharedPreferences p=RuntimeEnvironment.getApplication().getSharedPreferences("existing-chat",Context.MODE_PRIVATE);
        p.edit().putInt("fons_size",21).putInt("bubbleRadius",8).putBoolean("send_by_enter",true).commit();
        AxChatDefaults.apply(p);AxChatDefaults.apply(p);
        assertEquals(21,p.getInt("fons_size",0));assertEquals(8,p.getInt("bubbleRadius",0));assertTrue(p.getBoolean("send_by_enter",false));
    }
}
