package one.axgram.core.appearance;

import android.app.Application;
import android.view.View;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.MainTabsLayout;
import org.telegram.ui.Components.glass.GlassTabView;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35)
public class AxGlassNavigationSelectionTest {
    private MainTabsLayout layout;
    private View settings, calls;
    @Before public void setup() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        layout = new MainTabsLayout(RuntimeEnvironment.getApplication(), null);
        settings = new GlassTabView(RuntimeEnvironment.getApplication());
        calls = new GlassTabView(RuntimeEnvironment.getApplication());
        layout.addView(settings); layout.addView(calls);
        layout.setViewVisible(settings, true, false);
        layout.setViewVisible(calls, false, false);
    }
    private Object selected() throws Exception {
        Field field = MainTabsLayout.class.getDeclaredField("axSelectedTab");
        field.setAccessible(true); return field.get(layout);
    }
    @Test public void hiddenCallsButtonCannotStealSettingsSelection() throws Exception {
        layout.setAxSelectedTab(settings, false);
        layout.setAxSelectedTab(calls, false);
        assertSame(settings, selected());
    }
    @Test public void callsCanOwnSelectionWhenCallsAreActuallyShown() throws Exception {
        layout.setAxSelectedTab(settings, false);
        layout.setViewVisible(settings, false, false);
        layout.setViewVisible(calls, true, false);
        layout.setAxSelectedTab(calls, false);
        layout.setAxSelectedTab(settings, false);
        assertSame(calls, selected());
    }

    @Test public void programmaticPagerFramesDoNotSnapTheRunningSelectionAnimation() throws Exception {
        layout.setViewVisible(calls,true,false);
        layout.setBackground(new android.graphics.drawable.ColorDrawable(0xffeeeeee));
        layout.setAxGlassEnabled(true);
        layout.measure(View.MeasureSpec.makeMeasureSpec(420,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(84,View.MeasureSpec.EXACTLY));
        layout.layout(0,0,420,84);
        layout.setAxSelectedTab(settings,false);
        Field center=MainTabsLayout.class.getDeclaredField("axSelectionCenter");center.setAccessible(true);
        float start=center.getFloat(layout);
        layout.setAxSelectedTab(calls,true);
        layout.setAxGestureCenter(Float.NaN,false);
        assertEquals(start,center.getFloat(layout),.01f);
        layout.setAxGestureCenter(210,true);
        assertEquals(210,center.getFloat(layout),.01f);
    }
}
