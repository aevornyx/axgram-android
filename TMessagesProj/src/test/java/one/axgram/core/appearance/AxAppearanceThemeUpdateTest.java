package one.axgram.core.appearance;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.TextRadioCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BlurredRecyclerView;
import one.axgram.core.settings.AxAppearanceSettingsActivity;
import one.axgram.core.settings.BaseAxSettingsActivity;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, shadows = {
        AxAppearanceThemeUpdateTest.PreferencesOnlyController.class,
        AxAppearanceThemeUpdateTest.LocalStrings.class})
public class AxAppearanceThemeUpdateTest {
    @Test public void repeatedPanelToggleUpdatesSameAttachedControlsAndPercentageEnabledState() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationContext=context;
        org.telegram.ui.Cells.TextCheckCell toggle=new org.telegram.ui.Cells.TextCheckCell(context);
        TextSettingsCell percentage=new TextSettingsCell(context);
        java.lang.reflect.Method update=AxAppearanceSettingsActivity.class.getDeclaredMethod("syncPanelControls", android.view.View.class, android.view.View.class, boolean.class);
        update.setAccessible(true);
        for (boolean enabled:new boolean[]{false,true,false,true,false}) {
            update.invoke(null,toggle,percentage,enabled);
            assertEquals(enabled,toggle.isChecked());
            assertEquals(enabled,percentage.isEnabled());
            assertEquals(1f,percentage.getAlpha(),0f);
        }
    }
    @Implements(LocaleController.class)
    public static class LocalStrings {
        @Implementation public static String getString(int id) { return "Local test label"; }
        @Implementation public static String getString(String key, int id) { return "Local test label"; }
    }
    // The desktop test exercises the native color-binding classes, without starting Telegram's JNI/network engine.
    @Implements(MessagesController.class)
    public static class PreferencesOnlyController {
        @Implementation public static SharedPreferences getGlobalMainSettings() {
            return RuntimeEnvironment.getApplication().getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        }
        @Implementation public static SharedPreferences getGlobalNotificationsSettings() {
            return RuntimeEnvironment.getApplication().getSharedPreferences("Notifications", Context.MODE_PRIVATE);
        }
        @Implementation public static SharedPreferences getGlobalEmojiSettings() {
            return RuntimeEnvironment.getApplication().getSharedPreferences("emoji", Context.MODE_PRIVATE);
        }
    }
    private static void set(Object target, Class<?> type, String name, Object value) throws Exception {
        Field field=type.getDeclaredField(name); field.setAccessible(true); field.set(target,value);
    }
    private static TextView label(Object cell) throws Exception {
        Field field=cell.getClass().getDeclaredField("textView"); field.setAccessible(true);
        return (TextView)field.get(cell);
    }
    @Test public void animatedSettingsValuesReceiveThemeColorsWithoutRecreatingTheirCell() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationContext=context;
        AxAppearanceSettingsActivity fragment=new AxAppearanceSettingsActivity();
        FrameLayout root=new FrameLayout(context);
        BlurredRecyclerView list=new BlurredRecyclerView(context);
        list.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(context));
        TextSettingsCell value=new TextSettingsCell(context);
        value.setTextAndValue("Transparency","34%",false);list.addView(value);
        set(fragment,BaseFragment.class,"fragmentView",root);
        set(fragment,BaseFragment.class,"actionBar",new ActionBar(context));
        set(fragment,BaseAxSettingsActivity.class,"listView",list);
        Object same=value.getValueTextView();
        for(int blue:new int[]{0xff2d74ab,0xff64b5ef,0xff2d74ab}) {
            for(ThemeDescription description:fragment.getThemeDescriptions())
                if(description.getCurrentKey()==Theme.key_windowBackgroundWhiteValueText) description.setColor(blue,false,false);
            assertSame(same,value.getValueTextView());
            assertEquals(blue,value.getValueTextView().getPaint().getColor());
        }
    }
    @Test public void existingScreenBackgroundAndRowTextChangeTogetherWithoutReopening() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationContext=context;
        AxAppearanceSettingsActivity fragment=new AxAppearanceSettingsActivity();
        FrameLayout root=new FrameLayout(context);
        ActionBar bar=new ActionBar(context);
        BlurredRecyclerView list=new BlurredRecyclerView(context);
        list.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(context));
        TextRadioCell radio=new TextRadioCell(context);
        TextSettingsCell settings=new TextSettingsCell(context);
        list.addView(radio);list.addView(settings);
        set(fragment,BaseFragment.class,"fragmentView",root);
        set(fragment,BaseFragment.class,"actionBar",bar);
        set(fragment,BaseAxSettingsActivity.class,"listView",list);
        for (int[] palette:new int[][]{{0xffe8eef5,0xff172234},{0xff0b1016,0xffeaf0f6}}) {
            for (ThemeDescription description:fragment.getThemeDescriptions()) {
                if (description.getCurrentKey()==Theme.key_windowBackgroundGray) description.setColor(palette[0],false,false);
                if (description.getCurrentKey()==Theme.key_windowBackgroundWhiteBlackText) description.setColor(palette[1],false,false);
            }
            assertEquals(palette[0],((ColorDrawable)root.getBackground()).getColor());
            assertEquals(palette[1],label(radio).getCurrentTextColor());
            assertEquals(palette[1],label(settings).getCurrentTextColor());
        }
    }
}
