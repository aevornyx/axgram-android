package one.axgram.core.appearance;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.telegram.ui.ActionBar.Theme;

/** The renderer needs colours; the full Theme singleton also boots Telegram's native network stack. */
@Implements(Theme.class)
public class AxIconThemeShadow {
    private static final java.util.Map<Integer,Integer> colours=new java.util.HashMap<>();
    @Implementation protected static void __staticInitializer__() {
        // These keys are assigned at runtime by Theme; give them distinct values in isolation too.
        org.robolectric.util.ReflectionHelpers.setStaticField(Theme.class,"key_windowBackgroundWhite",100);
        org.robolectric.util.ReflectionHelpers.setStaticField(Theme.class,"key_windowBackgroundWhiteBlueText",101);
    }
    @Implementation protected static int getColor(int key) {
        return colours.getOrDefault(key,key==Theme.key_windowBackgroundWhite ? 0xfff2f4f7 : 0xff6bacc4);
    }
    @Implementation protected static void setColor(int key,int colour,boolean useDefault) { colours.put(key,colour); }
}
