package one.axgram.core.appearance;

import android.content.SharedPreferences;

/** AxGram's initial chat typography; called only by fresh-profile initialization. */
public final class AxChatDefaults {
    public static final int FONT_SIZE = 16;
    public static final int BUBBLE_RADIUS = 17;
    private AxChatDefaults() {}

    public static void apply(SharedPreferences preferences) {
        SharedPreferences.Editor editor = preferences.edit();
        if (!preferences.contains("fons_size")) editor.putInt("fons_size", FONT_SIZE);
        if (!preferences.contains("bubbleRadius")) editor.putInt("bubbleRadius", BUBBLE_RADIUS);
        editor.commit();
    }
}
