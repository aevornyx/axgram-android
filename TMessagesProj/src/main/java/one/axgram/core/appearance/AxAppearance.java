package one.axgram.core.appearance;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;

public final class AxAppearance {
    public static final String LIGHT = "AxGram Glass Light";
    public static final String DARK = "AxGram Glass Dark";
    public static final int MODE_LIGHT = 0;
    public static final int MODE_DARK = 1;
    public static final int MODE_SYSTEM = 2;
    private static final String[] MAIN_KEYS = {"theme", "nighttheme", "selectedAutoNightType"};
    private static final String[] THEME_KEYS = {"lastDayTheme", "lastDarkTheme"};

    private AxAppearance() {
    }

    public static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("axgram_appearance", Activity.MODE_PRIVATE);
    }

    public static boolean isGlassTheme() {
        Theme.ThemeInfo info = Theme.getActiveTheme();
        return info != null && (LIGHT.equals(info.getKey()) || DARK.equals(info.getKey()));
    }

    public static boolean useGlassNavigation() {
        return preferences().getBoolean("glass_navigation", isGlassTheme());
    }

    public static int mode() {
        return preferences().getInt("mode", MODE_SYSTEM);
    }

    public static boolean isModeDark(int mode) {
        return mode == MODE_DARK || mode == MODE_SYSTEM && isSystemDark();
    }

    public static boolean glassBlurEnabled() { return preferences().getBoolean("glass_blur", true); }
    public static int glassTransparency() { return Math.max(0, Math.min(80, preferences().getInt("glass_transparency", 15))); }
    public static int wallpaperBlur() { return Math.max(0, Math.min(100, preferences().getInt("wallpaper_blur", 0))); }
    public static int accentColor() { return preferences().getInt("accent_color", 0); }
    public static boolean listWallpaperVisible() { return preferences().getBoolean("list_wallpaper_visible", true); }

    public static void setListWallpaperVisible(boolean visible) {
        preferences().edit().putBoolean("list_wallpaper_visible", visible).commit();
    }

    public static Theme.ThemeInfo prepareQuickThemeSwitch(boolean dark) {
        int mode = dark ? MODE_DARK : MODE_LIGHT;
        preferences().edit().putInt("mode", mode).commit();
        configurePair(mode);
        return Theme.getTheme(dark ? DARK : LIGHT);
    }

    public static int glassColor(int original) {
        if (!isGlassTheme()) return original;
        if (!glassBlurEnabled()) return original | 0xff000000;
        if (!preferences().contains("glass_transparency")) return original;
        return androidx.core.graphics.ColorUtils.setAlphaComponent(original, Math.round(255 * (1f - glassTransparency() / 100f)));
    }

    public static void saveStyle(String key, int value) {
        if (!key.equals("accent_color") && !key.equals("glass_transparency") && !key.equals("wallpaper_blur")) throw new IllegalArgumentException(key);
        preferences().edit().putInt(key, value).commit();
        refreshStyle();
    }

    public static void setGlassBlur(boolean enabled) {
        preferences().edit().putBoolean("glass_blur", enabled).commit();
        // This is a material change, not a theme replacement. Keep the live view hierarchy.
    }

    public static void resetStyle() {
        preferences().edit().remove("accent_color").remove("glass_transparency").remove("wallpaper_blur").remove("glass_blur").commit();
        refreshStyle();
    }

    private static void refreshStyle() {
        if (!isGlassTheme()) return;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needSetDayNightTheme,
                Theme.getActiveTheme(), Theme.isCurrentThemeNight(), null, -1);
    }

    public static boolean hasPreviousTheme() {
        return preferences().getBoolean("previous_saved", false);
    }

    private static SharedPreferences themePreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("themeconfig", Activity.MODE_PRIVATE);
    }

    private static void rememberPreviousTheme() {
        if (isGlassTheme()) {
            return;
        }
        SharedPreferences.Editor editor = preferences().edit();
        saveKeys(MessagesController.getGlobalMainSettings(), editor, "main_", MAIN_KEYS);
        saveKeys(themePreferences(), editor, "theme_", THEME_KEYS);
        editor.putString("previous_day", Theme.getCurrentTheme().getKey());
        editor.putString("previous_night", Theme.getCurrentNightTheme().getKey());
        editor.putString("previous_active", Theme.getActiveTheme().getKey());
        editor.putBoolean("previous_is_night", Theme.isCurrentThemeNight());
        editor.putInt("previous_auto", Theme.selectedAutoNightType);
        editor.putBoolean("previous_navigation", useGlassNavigation());
        editor.putBoolean("previous_saved", true);
        editor.commit();
    }

    private static void saveKeys(SharedPreferences source, SharedPreferences.Editor target, String prefix, String[] keys) {
        for (String key : keys) {
            target.putBoolean(prefix + key + "_present", source.contains(key));
            Object value = source.getAll().get(key);
            if (value instanceof String) {
                target.putString(prefix + key, (String) value);
            } else if (value instanceof Integer) {
                target.putInt(prefix + key, (Integer) value);
            }
        }
    }

    private static void restoreKeys(SharedPreferences target, String prefix, String[] keys) {
        SharedPreferences snapshot = preferences();
        SharedPreferences.Editor editor = target.edit();
        for (String key : keys) {
            if (!snapshot.getBoolean(prefix + key + "_present", false)) {
                editor.remove(key);
            } else {
                Object value = snapshot.getAll().get(prefix + key);
                if (value instanceof String) {
                    editor.putString(key, (String) value);
                } else if (value instanceof Integer) {
                    editor.putInt(key, (Integer) value);
                }
            }
        }
        editor.commit();
    }

    public static void applyMode(int mode) {
        if (mode < MODE_LIGHT || mode > MODE_SYSTEM) {
            throw new IllegalArgumentException("Unknown appearance mode");
        }
        rememberPreviousTheme();
        preferences().edit().putInt("mode", mode).putBoolean("glass_navigation", true).commit();
        configurePair(mode);
        boolean night = isModeDark(mode);
        NotificationCenter.getGlobalInstance().postNotificationName(
                NotificationCenter.needSetDayNightTheme, Theme.getTheme(night ? DARK : LIGHT),
                mode == MODE_SYSTEM && night, null, -1);
    }

    private static boolean isSystemDark() {
        return (ApplicationLoader.applicationContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private static void configurePair(int mode) {
        Theme.setDayNightThemes(Theme.getTheme(LIGHT), Theme.getTheme(DARK));
        Theme.selectedAutoNightType = mode == MODE_SYSTEM ? Theme.AUTO_NIGHT_TYPE_SYSTEM : Theme.AUTO_NIGHT_TYPE_NONE;
        MessagesController.getGlobalMainSettings().edit()
                .putString("theme", mode == MODE_DARK ? DARK : LIGHT)
                .putString("nighttheme", DARK)
                .putInt("selectedAutoNightType", Theme.selectedAutoNightType).commit();
        themePreferences().edit().putString("lastDayTheme", LIGHT).putString("lastDarkTheme", DARK).commit();
        Theme.cancelAutoNightThemeCallbacks();
    }

    public static void restorePreviousTheme() {
        SharedPreferences prefs = preferences();
        if (!hasPreviousTheme()) {
            return;
        }
        Theme.ThemeInfo day = Theme.getTheme(prefs.getString("previous_day", "Blue"));
        Theme.ThemeInfo night = Theme.getTheme(prefs.getString("previous_night", "Dark Blue"));
        Theme.ThemeInfo active = Theme.getTheme(prefs.getString("previous_active", "Blue"));
        if (day == null) day = Theme.getTheme("Blue");
        if (night == null) night = Theme.getTheme("Dark Blue");
        if (active == null) active = day;
        restoreKeys(MessagesController.getGlobalMainSettings(), "main_", MAIN_KEYS);
        restoreKeys(themePreferences(), "theme_", THEME_KEYS);
        Theme.setDayNightThemes(day, night);
        Theme.selectedAutoNightType = prefs.getInt("previous_auto", Theme.AUTO_NIGHT_TYPE_NONE);
        prefs.edit().putBoolean("glass_navigation", prefs.getBoolean("previous_navigation", false))
                .putBoolean("previous_saved", false).commit();
        Theme.cancelAutoNightThemeCallbacks();
        NotificationCenter.getGlobalInstance().postNotificationName(
                NotificationCenter.needSetDayNightTheme, active, prefs.getBoolean("previous_is_night", false), null, -1);
    }

    public static void setGlassNavigation(boolean enabled) {
        preferences().edit().putBoolean("glass_navigation", enabled).commit();
        NotificationCenter.getGlobalInstance().postNotificationName(
                NotificationCenter.needSetDayNightTheme, Theme.getActiveTheme(), Theme.isCurrentThemeNight(), null, -1);
    }

    public static void initializeForNewProfile() {
        if (SharedConfig.activeAccounts == null || !SharedConfig.activeAccounts.isEmpty()
                || MessagesController.getGlobalMainSettings().contains("theme")
                || preferences().getBoolean("initialized", false)
                || Theme.getTheme(LIGHT) == null) {
            return;
        }
        preferences().edit().putBoolean("initialized", true).putBoolean("glass_navigation", true)
                .putInt("mode", MODE_SYSTEM).commit();
        SharedPreferences main = MessagesController.getGlobalMainSettings();
        AxChatDefaults.apply(main);
        SharedConfig.fontSize = main.getInt("fons_size", AxChatDefaults.FONT_SIZE);
        SharedConfig.fontSizeIsDefault = false;
        SharedConfig.ivFontSize = main.getInt("iv_font_size", SharedConfig.fontSize);
        SharedConfig.bubbleRadius = main.getInt("bubbleRadius", AxChatDefaults.BUBBLE_RADIUS);
        configurePair(MODE_SYSTEM);
        boolean dark = isSystemDark();
        Theme.applyTheme(Theme.getTheme(dark ? DARK : LIGHT), false, dark);
    }
}
