package one.axgram.core.appearance;

import android.graphics.Color;
import android.util.SparseIntArray;
import androidx.core.graphics.ColorUtils;
import org.telegram.ui.ActionBar.Theme;

public final class AxAppearanceColors {
    private AxAppearanceColors() {}

    public static int readableAccent(int requested, int background) {
        requested |= 0xff000000;
        background |= 0xff000000;
        if (ColorUtils.calculateContrast(requested, background) >= 4.5) return requested;
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(requested, hsl);
        float original = hsl[2];
        float endpoint = ColorUtils.calculateContrast(Color.BLACK, background)
                >= ColorUtils.calculateContrast(Color.WHITE, background) ? 0 : 1;
        float good = endpoint, bad = original;
        for (int i = 0; i < 16; i++) {
            hsl[2] = (good + bad) / 2f;
            if (ColorUtils.calculateContrast(ColorUtils.HSLToColor(hsl), background) >= 4.5) good = hsl[2];
            else bad = hsl[2];
        }
        hsl[2] = good;
        return ColorUtils.HSLToColor(hsl);
    }

    public static int contrastingText(int background) {
        return ColorUtils.calculateContrast(Color.WHITE, background | 0xff000000)
                >= ColorUtils.calculateContrast(Color.BLACK, background | 0xff000000) ? Color.WHITE : Color.BLACK;
    }

    private static int value(SparseIntArray colors, int key) {
        return colors.get(key, Theme.getDefaultColor(key));
    }

    public static void apply(SparseIntArray colors, boolean dark, int requested) {
        int listBackground = value(colors, Theme.key_windowBackgroundWhite);
        int card = colors.get(Theme.key_dialogBackground, listBackground);
        int contrastSurface = (ColorUtils.calculateLuminance(card) > ColorUtils.calculateLuminance(listBackground)) == dark
                ? card : listBackground;
        int accent = readableAccent(requested == 0 ? (dark ? 0xff85ccd9 : 0xff2d74ab) : requested,
                contrastSurface);
        colors.put(Theme.key_actionBarTabActiveText, readableAccent(value(colors, Theme.key_windowBackgroundWhiteBlackText),
                AxChatListStyle.selectedFill(listBackground, accent, dark)));
        colors.put(Theme.key_actionBarTabLine, accent);
        colors.put(Theme.key_chats_tabUnreadActiveBackground, accent);
        int[] foreground = {Theme.key_glass_tabSelected, Theme.key_glass_tabSelectedText,
                    Theme.key_windowBackgroundWhiteBlueText, Theme.key_windowBackgroundWhiteBlueText2,
                    Theme.key_windowBackgroundWhiteBlueText3, Theme.key_windowBackgroundWhiteBlueText4,
                    Theme.key_windowBackgroundWhiteBlueText5, Theme.key_windowBackgroundWhiteBlueText6,
                    Theme.key_windowBackgroundWhiteBlueText7, Theme.key_windowBackgroundWhiteBlueHeader,
                    Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_radioBackgroundChecked,
                    Theme.key_switchTrackChecked, Theme.key_switch2TrackChecked, Theme.key_chat_messagePanelSend,
                    Theme.key_featuredStickers_addButton, Theme.key_chat_inReplyNameText,
                    Theme.key_chat_messageLinkIn, Theme.key_chat_inAudioProgress, Theme.key_chat_inAudioSeekbarFill};
        for (int key : foreground) colors.put(key, accent);
        // Value links keep their blue text role by default; an explicit user tint can override it.
        colors.put(Theme.key_windowBackgroundWhiteValueText, requested == 0
                ? readableAccent(colors.get(Theme.key_windowBackgroundWhiteValueText,
                        dark ? 0xff64b5ef : 0xff2d74ab), contrastSurface) : accent);
        applyCommonComponents(colors, dark, accent, card);
        if (requested != 0) {
            float[] hue = new float[3]; ColorUtils.colorToHSL(requested, hue);
            for (int key : new int[]{Theme.key_chat_inBubble, Theme.key_chat_outBubble,
                    Theme.key_chat_inBubbleSelected, Theme.key_chat_outBubbleSelected}) {
                int old = value(colors, key); float[] hsl = new float[3]; ColorUtils.colorToHSL(old, hsl);
                if (hsl[1] > .05f) {
                    if (hue[1] < .05f) hsl[1] = 0;
                    hsl[0] = hue[0]; colors.put(key, ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(hsl), Color.alpha(old)));
                }
            }
            int outAccent = readableAccent(requested, value(colors, Theme.key_chat_outBubble));
            for (int key : new int[]{Theme.key_chat_messageLinkOut, Theme.key_chat_outReplyNameText,
                    Theme.key_chat_outSentCheck, Theme.key_chat_outSentCheckRead,
                    Theme.key_chat_outAudioProgress, Theme.key_chat_outAudioSeekbarFill}) colors.put(key, outAccent);
            colors.put(Theme.key_chats_unreadCounter, accent);
            colors.put(Theme.key_chats_unreadCounterText, contrastingText(accent));
            colors.put(Theme.key_telegram_color, accent);
            colors.put(Theme.key_chats_actionBackground, accent);
            colors.put(Theme.key_chats_actionIcon, contrastingText(accent));
            colors.put(Theme.key_featuredStickers_buttonText, contrastingText(accent));
        }
        // Loader and glyph form one control: never keep a white glyph on a white loader.
        int glyph = contrastingText(accent);
        for (int key : new int[]{Theme.key_chat_inLoader, Theme.key_chat_inLoaderSelected,
                Theme.key_chat_outLoader, Theme.key_chat_outLoaderSelected}) colors.put(key, accent);
        for (int key : new int[]{Theme.key_chat_inMediaIcon, Theme.key_chat_inMediaIconSelected,
                Theme.key_chat_outMediaIcon, Theme.key_chat_outMediaIconSelected,
                Theme.key_chat_inFileProgress, Theme.key_chat_inFileProgressSelected,
                Theme.key_chat_outFileProgress, Theme.key_chat_outFileProgressSelected}) colors.put(key, glyph);
    }

    private static void applyCommonComponents(SparseIntArray colors, boolean dark, int accent, int card) {
        int primary = colors.get(Theme.key_windowBackgroundWhiteBlackText, dark ? 0xffeaf0f6 : 0xff172234);
        int secondary = colors.get(Theme.key_windowBackgroundWhiteGrayText, dark ? 0xffa0adbb : 0xff607185);
        int glyph = contrastingText(accent);
        for (int key : new int[]{Theme.key_dialogButton, Theme.key_dialogTextBlue,
                Theme.key_dialogCheckboxSquareBackground, Theme.key_checkbox, Theme.key_checkboxSquareBackground,
                Theme.key_switchTrackBlueChecked, Theme.key_player_progress, Theme.key_player_buttonActive,
                Theme.key_chats_actionBackground}) colors.put(key, accent);
        for (int key : new int[]{Theme.key_dialogCheckboxSquareCheck, Theme.key_checkboxCheck,
                Theme.key_checkboxSquareCheck, Theme.key_switchTrackBlueThumbChecked,
                Theme.key_chats_actionIcon, Theme.key_featuredStickers_buttonText}) colors.put(key, glyph);
        // The full audio player belongs to the same surface family as menus/dialogs.
        colors.put(Theme.key_player_background, card);
        colors.put(Theme.key_player_actionBarTitle, primary);
        colors.put(Theme.key_player_actionBarSubtitle, secondary);
        colors.put(Theme.key_player_actionBarItems, primary);
        colors.put(Theme.key_player_button, primary);
        colors.put(Theme.key_player_time, secondary);
        colors.put(Theme.key_player_progressBackground, ColorUtils.blendARGB(card, accent, .25f));
        colors.put(Theme.key_player_progressCachedBackground, ColorUtils.blendARGB(card, accent, .45f));
        colors.put(Theme.key_player_actionBarSelector, ColorUtils.setAlphaComponent(accent, 32));
        colors.put(Theme.key_inappPlayerBackground, card);
        colors.put(Theme.key_inappPlayerTitle, primary);
        colors.put(Theme.key_inappPlayerPerformer, secondary);
        colors.put(Theme.key_inappPlayerPlayPause, accent);
        colors.put(Theme.key_inappPlayerClose, secondary);
    }
}
