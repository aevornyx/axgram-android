package one.axgram.core.appearance;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import androidx.core.graphics.ColorUtils;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.ActionBar.Theme;

/** Shared material and typography for the chat-list surfaces, without changing actions/layout. */
public final class AxChatListStyle {
    private static Typeface regular, medium, semibold;
    private static final java.util.IdentityHashMap<android.graphics.Paint, Typeface> nativeFonts = new java.util.IdentityHashMap<>();
    private AxChatListStyle() {}

    public static Typeface font(int weight) {
        if (!AxAppearance.isGlassTheme()) return weight >= 500 ? AndroidUtilities.bold() : Typeface.DEFAULT;
        if (regular == null) {
            try {
                regular = Typeface.createFromAsset(ApplicationLoader.applicationContext.getAssets(), "fonts/axgram_onest.ttf");
                medium = Build.VERSION.SDK_INT >= 28 ? Typeface.create(regular, 500, false) : regular;
                semibold = Build.VERSION.SDK_INT >= 28 ? Typeface.create(regular, 600, false) : Typeface.create(regular, Typeface.BOLD);
            } catch (RuntimeException e) {
                regular = Typeface.DEFAULT; medium = semibold = AndroidUtilities.bold();
            }
        }
        return weight >= 600 ? semibold : weight >= 500 ? medium : regular;
    }

    public static int accent() {
        int requested = AxAppearance.accentColor();
        return AxAppearanceColors.readableAccent(requested != 0 ? requested : Theme.getColor(Theme.key_glass_tabSelected),
                Theme.getColor(Theme.key_windowBackgroundWhite));
    }

    public static int selectedFill(int background, int accent, boolean dark) {
        return ColorUtils.blendARGB(background | 0xff000000, accent | 0xff000000, dark ? .18f : .16f);
    }

    public static int selectedText(int background, int accent, boolean dark) {
        return AxAppearanceColors.readableAccent(accent, selectedFill(background, accent, dark));
    }

    private static void applyFont(android.graphics.Paint paint, int weight) {
        if (paint == null) return;
        if (AxAppearance.isGlassTheme()) {
            if (!nativeFonts.containsKey(paint)) nativeFonts.put(paint, paint.getTypeface());
            paint.setTypeface(font(weight));
        } else if (nativeFonts.containsKey(paint)) {
            paint.setTypeface(nativeFonts.remove(paint));
        }
    }

    public static void applyDialogTypography(boolean resourcesInitialized) {
        if (Theme.dialogs_namePaint == null || !resourcesInitialized && nativeFonts.isEmpty()) return;
        for (int i = 0; i < 2; i++) {
            applyFont(Theme.dialogs_namePaint[i], 600);
            applyFont(Theme.dialogs_nameEncryptedPaint[i], 600);
            applyFont(Theme.dialogs_messagePaint[i], 400);
            applyFont(Theme.dialogs_messagePrintingPaint[i], 400);
        }
        applyFont(Theme.dialogs_countTextPaint, 500);
        applyFont(Theme.dialogs_countTextPaint2, 500);
        applyFont(Theme.dialogs_timePaint, 400);
        applyFont(Theme.dialogs_timePaintBold, 500);
        applyFont(Theme.dialogs_timePaintBoldAccent, 500);
        applyFont(Theme.dialogs_messageNamePaint, 500);
    }
}
