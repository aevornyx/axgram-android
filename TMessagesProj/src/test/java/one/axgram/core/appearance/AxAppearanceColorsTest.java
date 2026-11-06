package one.axgram.core.appearance;

import android.app.Application;
import android.graphics.Color;
import android.util.SparseIntArray;
import androidx.core.graphics.ColorUtils;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.ui.ActionBar.Theme;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, shadows = {
        AxAppearanceThemeUpdateTest.PreferencesOnlyController.class, AxAppearanceThemeUpdateTest.LocalStrings.class})
public class AxAppearanceColorsTest {
    @Before public void setup() {
        org.telegram.messenger.ApplicationLoader.applicationContext = org.robolectric.RuntimeEnvironment.getApplication();
    }
    @Test public void arbitraryUserColorsRemainReadableOnLightAndDarkSurfaces() {
        int[] backgrounds={0xfff1f5fa,0xff10161d,0xffc9e4f9,0xff21414f};
        for (int hue=0;hue<360;hue+=15) for (float light:new float[]{.1f,.5f,.9f}) {
            int requested=ColorUtils.HSLToColor(new float[]{hue,.85f,light});
            for (int background:backgrounds) assertTrue(ColorUtils.calculateContrast(
                    AxAppearanceColors.readableAccent(requested,background),background)>=4.5);
        }
        for (int requested:new int[]{Color.WHITE,Color.BLACK,Color.GRAY}) for (int background:backgrounds)
            assertTrue(ColorUtils.calculateContrast(AxAppearanceColors.readableAccent(requested,background),background)>=4.5);
    }
    @Test public void downloadedDownloadAndCancelGlyphColorsRemainVisibleInBothStates() {
        for (boolean dark:new boolean[]{false,true}) for (int chosen:new int[]{0,0xffffffff,0xff000000,0xffff0000,0xff8545e0}) {
            SparseIntArray colors=new SparseIntArray();
            colors.put(Theme.key_windowBackgroundWhite,dark?0xff10161d:0xfff1f5fa);
            colors.put(Theme.key_chat_outBubble,dark?0xff21414f:0xffc9e4f9);
            AxAppearanceColors.apply(colors,dark,chosen);
            for (int[] pair:new int[][]{
                    {Theme.key_chat_outMediaIcon,Theme.key_chat_outLoader},
                    {Theme.key_chat_outMediaIconSelected,Theme.key_chat_outLoaderSelected},
                    {Theme.key_chat_inMediaIcon,Theme.key_chat_inLoader},
                    {Theme.key_chat_inMediaIconSelected,Theme.key_chat_inLoaderSelected},
                    {Theme.key_chat_outFileProgress,Theme.key_chat_outLoader},
                    {Theme.key_chat_inFileProgress,Theme.key_chat_inLoader}})
                assertTrue(ColorUtils.calculateContrast(colors.get(pair[0]),colors.get(pair[1]))>=4.5);
        }
    }
    @Test public void accentDoesNotRecolorErrorTextOrOrdinaryMessageText() {
        SparseIntArray colors=new SparseIntArray();
        colors.put(Theme.key_windowBackgroundWhite,0xfff1f5fa);
        colors.put(Theme.key_windowBackgroundWhiteRedText3,0xffd32f2f);
        colors.put(Theme.key_chat_messageTextIn,0xff172234);
        AxAppearanceColors.apply(colors,false,0xff8545e0);
        assertEquals(0xffd32f2f,colors.get(Theme.key_windowBackgroundWhiteRedText3));
        assertEquals(0xff172234,colors.get(Theme.key_chat_messageTextIn));
    }
    @Test public void defaultAndCustomAccentsStayReadableAcrossSettingsDialogsAndPlayer() {
        for (boolean dark : new boolean[]{false,true}) for (int chosen : new int[]{0,Color.WHITE,Color.BLACK,Color.RED,0xff8545e0}) {
            SparseIntArray colors = new SparseIntArray();
            int page = dark ? 0xff10161d : 0xfff1f5fa, card = dark ? 0xff18212a : 0xfff7fafd;
            colors.put(Theme.key_windowBackgroundWhite,page); colors.put(Theme.key_dialogBackground,card);
            colors.put(Theme.key_windowBackgroundWhiteBlackText,dark ? 0xffeaf0f6 : 0xff172234);
            AxAppearanceColors.apply(colors,dark,chosen);
            for (int key : new int[]{Theme.key_dialogButton,Theme.key_dialogTextBlue})
                assertTrue(ColorUtils.calculateContrast(colors.get(key),card)>=4.5);
            assertTrue(ColorUtils.calculateContrast(colors.get(Theme.key_windowBackgroundWhiteBlueText),page)>=4.5);
            assertTrue(ColorUtils.calculateContrast(colors.get(Theme.key_windowBackgroundWhiteValueText),page)>=4.5);
            if (chosen != 0) assertEquals(colors.get(Theme.key_dialogTextBlue),colors.get(Theme.key_windowBackgroundWhiteValueText));
            assertEquals(colors.get(Theme.key_player_background),colors.get(Theme.key_inappPlayerBackground));
            assertTrue(ColorUtils.calculateContrast(colors.get(Theme.key_inappPlayerTitle),colors.get(Theme.key_inappPlayerBackground))>=4.5);
            assertTrue(ColorUtils.calculateContrast(colors.get(Theme.key_inappPlayerPlayPause),colors.get(Theme.key_inappPlayerBackground))>=4.5);
            assertTrue(ColorUtils.calculateContrast(colors.get(Theme.key_player_button),colors.get(Theme.key_player_background))>=4.5);
            for (int[] pair : new int[][]{{Theme.key_checkboxCheck,Theme.key_checkbox},
                    {Theme.key_dialogCheckboxSquareCheck,Theme.key_dialogCheckboxSquareBackground},
                    {Theme.key_checkboxSquareCheck,Theme.key_checkboxSquareBackground},
                    {Theme.key_chats_actionIcon,Theme.key_chats_actionBackground}})
                assertTrue(ColorUtils.calculateContrast(colors.get(pair[0]),colors.get(pair[1]))>=4.5);
        }
    }
}
