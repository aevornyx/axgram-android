package one.axgram.core.appearance;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.SparseIntArray;
import android.widget.ImageView;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import java.util.WeakHashMap;

/** Optional static UI icon family. Native animations, photos and unmapped symbols stay intact. */
public final class AxIconPack {
    private static final SparseIntArray icons = createMap();
    private static final WeakHashMap<IconDrawable, Boolean> visibleIcons = new WeakHashMap<>();
    private AxIconPack() {}
    public static boolean enabled() {
        return ApplicationLoader.applicationContext == null || AxAppearance.preferences().getBoolean("modern_icons", true);
    }
    public static void setEnabled(boolean enabled) {
        AxAppearance.preferences().edit().putBoolean("modern_icons", enabled).commit();
        IconDrawable[] snapshot;
        synchronized (visibleIcons) { snapshot=visibleIcons.keySet().toArray(new IconDrawable[0]); }
        for (IconDrawable icon : snapshot) if (icon != null) icon.invalidateSelf();
    }
    public static int resolve(int original) { return enabled() ? icons.get(original, original) : original; }
    public static Drawable drawable(Context context, int original) {
        if (original == 0) return null;
        Drawable stock = context.getResources().getDrawable(original).mutate();
        int modern = icons.get(original, 0);
        return modern == 0 ? stock : new IconDrawable(stock, context.getResources().getDrawable(modern).mutate());
    }
    public static boolean hasIcon(int original) { return icons.indexOfKey(original) >= 0; }
    /** Decorative size is opt-in: toolbar and dense menu glyphs remain legible. */
    public static Drawable badge(Context context, int original) {
        Drawable stock=context.getResources().getDrawable(original).mutate();
        int resource=icons.get(original,0);
        return resource==0 ? stock : new IconDrawable(stock,new AxGlassIcon(context.getResources().getDrawable(resource).mutate()));
    }
    public static boolean isPackDrawable(Drawable drawable) { return drawable instanceof IconDrawable; }
    public static void bind(ImageView view, int original) {
        if (icons.indexOfKey(original) < 0) view.setImageResource(original);
        else view.setImageDrawable(drawable(view.getContext(), original));
    }
    private static SparseIntArray createMap() {
        SparseIntArray result = new SparseIntArray();
        result.put(R.drawable.settings_account, R.drawable.ax_icon_user_round);
        result.put(R.drawable.settings_chat, R.drawable.ax_icon_paintbrush);
        result.put(R.drawable.settings_privacy, R.drawable.ax_icon_shield_check);
        result.put(R.drawable.settings_sounds, R.drawable.ax_icon_bell_ring);
        result.put(R.drawable.settings_data, R.drawable.ax_icon_database);
        result.put(R.drawable.settings_folders, R.drawable.ax_icon_folders);
        result.put(R.drawable.settings_devices, R.drawable.ax_icon_monitor_smartphone);
        result.put(R.drawable.settings_power, R.drawable.ax_icon_battery_charging);
        result.put(R.drawable.settings_language, R.drawable.ax_icon_languages);
        result.put(R.drawable.settings_premium, R.drawable.ax_icon_sparkles);
        result.put(R.drawable.settings_stars, R.drawable.ax_icon_star);
        result.put(R.drawable.settings_gram_24, R.drawable.ax_icon_wallet);
        result.put(R.drawable.settings_business, R.drawable.ax_icon_briefcase_business);
        result.put(R.drawable.settings_gift, R.drawable.ax_icon_gift);
        result.put(R.drawable.settings_ask, R.drawable.ax_icon_messages_square);
        result.put(R.drawable.settings_faq, R.drawable.ax_icon_circle_help);
        result.put(R.drawable.settings_features, R.drawable.ax_icon_wand_sparkles);
        result.put(R.drawable.settings_policy, R.drawable.ax_icon_file_lock2);
        result.put(R.drawable.settings_wallet, R.drawable.ax_icon_wallet);
        result.put(R.drawable.msg_settings, R.drawable.ax_icon_sliders_horizontal);
        result.put(R.drawable.msg_notifications, R.drawable.ax_icon_bell);
        result.put(R.drawable.msg_contacts, R.drawable.ax_icon_contact_round);
        result.put(R.drawable.msg_calls, R.drawable.ax_icon_phone);
        result.put(R.drawable.msg_videocall, R.drawable.ax_icon_video);
        result.put(R.drawable.msg_edit, R.drawable.ax_icon_square_pen);
        result.put(R.drawable.msg_copy, R.drawable.ax_icon_copy);
        result.put(R.drawable.msg_link, R.drawable.ax_icon_link2);
        result.put(R.drawable.msg_link2, R.drawable.ax_icon_link2);
        result.put(R.drawable.msg_share, R.drawable.ax_icon_share2);
        result.put(R.drawable.msg_forward, R.drawable.ax_icon_forward);
        result.put(R.drawable.msg_search, R.drawable.ax_icon_search);
        result.put(R.drawable.msg_delete, R.drawable.ax_icon_trash2);
        result.put(R.drawable.msg_clear, R.drawable.ax_icon_eraser);
        result.put(R.drawable.msg_block, R.drawable.ax_icon_ban);
        result.put(R.drawable.msg_log, R.drawable.ax_icon_scroll_text);
        result.put(R.drawable.msg_admins, R.drawable.ax_icon_badge_check);
        result.put(R.drawable.msg_permissions, R.drawable.ax_icon_key_round);
        result.put(R.drawable.msg_channel, R.drawable.ax_icon_radio);
        result.put(R.drawable.msg_qrcode, R.drawable.ax_icon_qr_code);
        result.put(R.drawable.msg_addcontact, R.drawable.ax_icon_user_round_plus);
        result.put(R.drawable.msg_leave, R.drawable.ax_icon_log_out);
        result.put(R.drawable.msg_secret, R.drawable.ax_icon_lock_keyhole);
        result.put(R.drawable.msg_silent, R.drawable.ax_icon_bell_off);
        result.put(R.drawable.msg_pin, R.drawable.ax_icon_pin);
        result.put(R.drawable.msg_unpin, R.drawable.ax_icon_pin_off);
        result.put(R.drawable.msg_archive, R.drawable.ax_icon_archive);
        result.put(R.drawable.msg_unarchive, R.drawable.ax_icon_archive_restore);
        result.put(R.drawable.msg_saved, R.drawable.ax_icon_bookmark);
        result.put(R.drawable.msg_fave, R.drawable.ax_icon_star);
        result.put(R.drawable.msg_autodelete, R.drawable.ax_icon_timer);
        result.put(R.drawable.msg_retry, R.drawable.ax_icon_rotate_ccw);
        result.put(R.drawable.msg_info, R.drawable.ax_icon_info);
        result.put(R.drawable.msg_help, R.drawable.ax_icon_circle_help);
        result.put(R.drawable.msg_reset, R.drawable.ax_icon_rotate_ccw);
        result.put(R.drawable.msg_translate, R.drawable.ax_icon_languages);
        result.put(R.drawable.msg_download, R.drawable.ax_icon_download);
        result.put(R.drawable.msg_location, R.drawable.ax_icon_map_pin);
        result.put(R.drawable.msg_remove, R.drawable.ax_icon_user_round_minus);
        result.put(R.drawable.msg_report, R.drawable.ax_icon_flag);
        result.put(R.drawable.msg_stats, R.drawable.ax_icon_chart_no_axes_combined);
        result.put(R.drawable.msg_mute, R.drawable.ax_icon_bell_off);
        result.put(R.drawable.msg_addbot, R.drawable.ax_icon_bot);
        result.put(R.drawable.msg_voicechat2, R.drawable.ax_icon_audio_lines);
        result.put(R.drawable.msg_mini_autodelete_timer, R.drawable.ax_icon_timer);
        result.put(R.drawable.ic_ab_other, R.drawable.ax_icon_ellipsis_vertical);
        result.put(R.drawable.outline_header_qr_24, R.drawable.ax_icon_qr_code);
        result.put(R.drawable.profile_newmsg, R.drawable.ax_icon_square_pen);
        result.put(R.drawable.profile_discuss, R.drawable.ax_icon_messages_square);
        result.put(R.drawable.call, R.drawable.ax_icon_phone_call);
        result.put(R.drawable.menu_chats, R.drawable.ax_icon_messages_square);
        result.put(R.drawable.menu_gift, R.drawable.ax_icon_gift);
        result.put(R.drawable.menu_refund, R.drawable.ax_icon_undo2);
        result.put(R.drawable.menu_shop, R.drawable.ax_icon_shopping_bag);
        result.put(R.drawable.menu_privacy_policy, R.drawable.ax_icon_file_lock2);
        result.put(R.drawable.menu_profile_colors, R.drawable.ax_icon_palette);
        result.put(R.drawable.menu_storage_path, R.drawable.ax_icon_folder_open);
        result.put(R.drawable.menu_tag_edit, R.drawable.ax_icon_tag);
        result.put(R.drawable.menu_tag_plus, R.drawable.ax_icon_tag);
        result.put(R.drawable.menu_username_change, R.drawable.ax_icon_at_sign);
        result.put(R.drawable.menu_username_set, R.drawable.ax_icon_at_sign);
        result.put(R.drawable.msg_add, R.drawable.ax_icon_plus);
        result.put(R.drawable.msg_addphoto, R.drawable.ax_icon_image_plus);
        result.put(R.drawable.msg_arrowright, R.drawable.ax_icon_chevron_right);
        result.put(R.drawable.msg_block2, R.drawable.ax_icon_ban);
        result.put(R.drawable.msg_calls_regular, R.drawable.ax_icon_phone_call);
        result.put(R.drawable.msg_clearcache, R.drawable.ax_icon_brush_cleaning);
        result.put(R.drawable.msg_contact_add, R.drawable.ax_icon_user_round_plus);
        result.put(R.drawable.msg_customize, R.drawable.ax_icon_palette);
        result.put(R.drawable.msg_disable, R.drawable.ax_icon_circle_minus);
        result.put(R.drawable.msg_discussion, R.drawable.ax_icon_messages_square);
        result.put(R.drawable.msg_folders, R.drawable.ax_icon_folders);
        result.put(R.drawable.msg_gallery, R.drawable.ax_icon_images);
        result.put(R.drawable.msg_groups, R.drawable.ax_icon_users_round);
        result.put(R.drawable.msg_groups_create, R.drawable.ax_icon_users_round);
        result.put(R.drawable.msg_home, R.drawable.ax_icon_house);
        result.put(R.drawable.msg_map, R.drawable.ax_icon_map_pinned);
        result.put(R.drawable.msg_markunread, R.drawable.ax_icon_mail);
        result.put(R.drawable.msg_media, R.drawable.ax_icon_image);
        result.put(R.drawable.msg_openprofile, R.drawable.ax_icon_contact_round);
        result.put(R.drawable.msg_policy, R.drawable.ax_icon_shield_check);
        result.put(R.drawable.msg_requests, R.drawable.ax_icon_user_round_search);
        result.put(R.drawable.msg_shareout, R.drawable.ax_icon_share2);
        result.put(R.drawable.msg_sticker, R.drawable.ax_icon_sticker);
        result.put(R.drawable.msg_theme, R.drawable.ax_icon_palette);
        result.put(R.drawable.msg_topics, R.drawable.ax_icon_list_tree);
        result.put(R.drawable.msg_user_remove, R.drawable.ax_icon_user_round_minus);
        result.put(R.drawable.msg_view_file, R.drawable.ax_icon_file_text);
        result.put(R.drawable.msg_voicechat, R.drawable.ax_icon_audio_lines);
        result.put(R.drawable.outline_header_search, R.drawable.ax_icon_search);
        result.put(R.drawable.profile_admin, R.drawable.ax_icon_shield_check);
        result.put(R.drawable.profile_link, R.drawable.ax_icon_link2);
        result.put(R.drawable.profile_video, R.drawable.ax_icon_video);
        result.put(R.drawable.settings_calls, R.drawable.ax_icon_phone);
        result.put(R.drawable.filled_chatlist_mention, R.drawable.ax_icon_at_sign);
        result.put(R.drawable.filled_birthday, R.drawable.ax_icon_cake);
        result.put(R.drawable.msg_filled_menu_channels, R.drawable.ax_icon_radio);
        result.put(R.drawable.filled_premium_hours, R.drawable.ax_icon_clock);
        result.put(R.drawable.filled_location, R.drawable.ax_icon_map_pin);
        result.put(R.drawable.premium_ai_editor, R.drawable.ax_icon_bot);
        result.put(R.drawable.outline_add_account, R.drawable.ax_icon_user_round_plus);
        result.put(R.drawable.group_edit_profile, R.drawable.ax_icon_square_pen);
        result.put(R.drawable.msg_archive_hide, R.drawable.ax_icon_archive_x);
        result.put(R.drawable.msg_channel_create, R.drawable.ax_icon_radio);
        result.put(R.drawable.filled_premium_camera, R.drawable.ax_icon_camera);
        result.put(R.drawable.filled_profile_message_24, R.drawable.ax_icon_messages_square);
        result.put(R.drawable.outline_profile_message_24, R.drawable.ax_icon_messages_square);
        result.put(R.drawable.filled_profile_mute_24, R.drawable.ax_icon_bell_off);
        result.put(R.drawable.outline_profile_mute_24, R.drawable.ax_icon_bell_off);
        result.put(R.drawable.filled_profile_unmute_24, R.drawable.ax_icon_bell);
        result.put(R.drawable.outline_profile_unmute_24, R.drawable.ax_icon_bell);
        result.put(R.drawable.filled_profile_call_24, R.drawable.ax_icon_phone);
        result.put(R.drawable.outline_profile_call_24, R.drawable.ax_icon_phone);
        result.put(R.drawable.filled_profile_video_24, R.drawable.ax_icon_video);
        result.put(R.drawable.outline_profile_video_24, R.drawable.ax_icon_video);
        result.put(R.drawable.filled_profile_member_24, R.drawable.ax_icon_user_round_plus);
        result.put(R.drawable.outline_profile_member_24, R.drawable.ax_icon_user_round_plus);
        result.put(R.drawable.filled_profile_story, R.drawable.ax_icon_circle_plus);
        result.put(R.drawable.outline_profile_story, R.drawable.ax_icon_circle_plus);
        result.put(R.drawable.filled_profile_stop_24, R.drawable.ax_icon_square);
        result.put(R.drawable.outline_profile_stop_24, R.drawable.ax_icon_square);
        result.put(R.drawable.filled_profile_photo, R.drawable.ax_icon_camera);
        result.put(R.drawable.outline_profile_photo, R.drawable.ax_icon_camera);
        result.put(R.drawable.filled_profile_edit_24, R.drawable.ax_icon_square_pen);
        result.put(R.drawable.outline_profile_edit_24, R.drawable.ax_icon_square_pen);
        result.put(R.drawable.filled_profile_settings, R.drawable.ax_icon_sliders_horizontal);
        result.put(R.drawable.outline_profile_settings, R.drawable.ax_icon_sliders_horizontal);
        result.put(R.drawable.gift, R.drawable.ax_icon_gift);
        result.put(R.drawable.input_gift_s, R.drawable.ax_icon_gift);
        result.put(R.drawable.action_share, R.drawable.ax_icon_share2);
        result.put(R.drawable.report, R.drawable.ax_icon_flag);
        result.put(R.drawable.leave, R.drawable.ax_icon_log_out);
        result.put(R.drawable.live_stream, R.drawable.ax_icon_audio_lines);
        result.put(R.drawable.msg2_animations, R.drawable.ax_icon_wand_sparkles);
        result.put(R.drawable.msg2_archived_stickers, R.drawable.ax_icon_archive);
        result.put(R.drawable.msg2_ask_question, R.drawable.ax_icon_messages_square);
        result.put(R.drawable.msg2_autodelete, R.drawable.ax_icon_timer);
        result.put(R.drawable.msg2_battery, R.drawable.ax_icon_battery_charging);
        result.put(R.drawable.msg2_block2, R.drawable.ax_icon_ban);
        result.put(R.drawable.msg2_call_earpiece, R.drawable.ax_icon_phone);
        result.put(R.drawable.msg2_chats_add, R.drawable.ax_icon_messages_square);
        result.put(R.drawable.msg2_data, R.drawable.ax_icon_database);
        result.put(R.drawable.msg2_devices, R.drawable.ax_icon_monitor_smartphone);
        result.put(R.drawable.msg2_discussion, R.drawable.ax_icon_messages_square);
        result.put(R.drawable.msg2_email, R.drawable.ax_icon_mail);
        result.put(R.drawable.msg2_folder, R.drawable.ax_icon_folders);
        result.put(R.drawable.msg2_help, R.drawable.ax_icon_circle_help);
        result.put(R.drawable.msg2_language, R.drawable.ax_icon_languages);
        result.put(R.drawable.msg2_link2, R.drawable.ax_icon_link2);
        result.put(R.drawable.msg2_notifications, R.drawable.ax_icon_bell);
        result.put(R.drawable.msg2_permissions, R.drawable.ax_icon_key_round);
        result.put(R.drawable.msg2_policy, R.drawable.ax_icon_shield_check);
        result.put(R.drawable.msg2_reactions2, R.drawable.ax_icon_sparkles);
        result.put(R.drawable.msg2_secret, R.drawable.ax_icon_lock_keyhole);
        result.put(R.drawable.msg2_smile_status, R.drawable.ax_icon_sparkles);
        result.put(R.drawable.msg2_sticker, R.drawable.ax_icon_sticker);
        result.put(R.drawable.msg2_trending, R.drawable.ax_icon_chart_no_axes_combined);
        result.put(R.drawable.msg2_videocall, R.drawable.ax_icon_video);
        result.put(R.drawable.menu_2sv, R.drawable.ax_icon_shield_check);
        result.put(R.drawable.menu_2sv_on, R.drawable.ax_icon_shield_check);
        result.put(R.drawable.settings_arrow, R.drawable.ax_icon_chevron_right);
        result.put(R.drawable.settings_channel, R.drawable.ax_icon_radio);
        result.put(R.drawable.settings_group, R.drawable.ax_icon_users_round);
        result.put(R.drawable.settings_invite, R.drawable.ax_icon_user_round_plus);
        result.put(R.drawable.baseline_security_24, R.drawable.ax_icon_shield_check);
        return result;
    }
    static final class IconDrawable extends Drawable implements Drawable.Callback {
        final Drawable stock, modern;
        IconDrawable(Drawable stock, Drawable modern) {
            this.stock = stock; this.modern = modern;
            stock.setCallback(this); modern.setCallback(this);
            modern.setAutoMirrored(stock.isAutoMirrored());
            synchronized (visibleIcons) { visibleIcons.put(this, Boolean.TRUE); }
        }
        @Override public void draw(Canvas canvas) { (enabled() ? modern : stock).draw(canvas); }
        @Override protected void onBoundsChange(Rect bounds) { stock.setBounds(bounds); modern.setBounds(bounds); }
        @Override public int getIntrinsicWidth() { return stock.getIntrinsicWidth(); }
        @Override public int getIntrinsicHeight() { return stock.getIntrinsicHeight(); }
        @Override public boolean getPadding(Rect padding) { return stock.getPadding(padding); }
        @Override public void setAlpha(int alpha) { stock.setAlpha(alpha); modern.setAlpha(alpha); invalidateSelf(); }
        @Override public int getAlpha() { return stock.getAlpha(); }
        @Override public void setColorFilter(ColorFilter filter) { stock.setColorFilter(filter); modern.setColorFilter(filter); invalidateSelf(); }
        @Override public ColorFilter getColorFilter() { return stock.getColorFilter(); }
        @Override public void setTint(int tint) { stock.setTint(tint); modern.setTint(tint); }
        @Override public void setTintList(ColorStateList tint) { stock.setTintList(tint); modern.setTintList(tint); }
        @Override public void setTintMode(PorterDuff.Mode mode) { stock.setTintMode(mode); modern.setTintMode(mode); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public ConstantState getConstantState() {
            ConstantState a=stock.getConstantState(),b=modern.getConstantState();
            return a==null||b==null ? null : new IconState(a,b,getChangingConfigurations());
        }
        @Override public boolean isStateful() { return stock.isStateful() || modern.isStateful(); }
        @Override protected boolean onStateChange(int[] state) { boolean a = stock.setState(state), b = modern.setState(state); return a || b; }
        @Override protected boolean onLevelChange(int level) { boolean a = stock.setLevel(level), b = modern.setLevel(level); return a || b; }
        @Override public boolean onLayoutDirectionChanged(int direction) { boolean a = stock.setLayoutDirection(direction), b = modern.setLayoutDirection(direction); return a || b; }
        @Override public void jumpToCurrentState() { stock.jumpToCurrentState(); modern.jumpToCurrentState(); }
        @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
        @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
        @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
    }
    private static final class IconState extends Drawable.ConstantState {
        final Drawable.ConstantState stock,modern; final int configurations;
        IconState(Drawable.ConstantState stock,Drawable.ConstantState modern,int configurations) {
            this.stock=stock;this.modern=modern;this.configurations=configurations;
        }
        @Override public Drawable newDrawable() { return new IconDrawable(stock.newDrawable().mutate(),modern.newDrawable().mutate()); }
        @Override public Drawable newDrawable(Resources resources) { return new IconDrawable(stock.newDrawable(resources).mutate(),modern.newDrawable(resources).mutate()); }
        @Override public int getChangingConfigurations() { return configurations; }
    }
}
