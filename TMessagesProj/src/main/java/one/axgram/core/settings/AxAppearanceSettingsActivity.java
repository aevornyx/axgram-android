package one.axgram.core.settings;

import android.content.Context;
import android.view.View;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.ColorPicker;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SeekBarView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextRadioCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.ThemeActivity;
import org.telegram.ui.WallpapersListActivity;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;

import java.util.ArrayList;

import one.axgram.core.appearance.AxAppearance;

public final class AxAppearanceSettingsActivity extends BaseAxSettingsActivity {
    private int headerRow, lightRow, darkRow, systemRow, navigationRow, infoRow, dividerRow, restoreRow, telegramRow;
    private int accentRow, glassBlurRow, transparencyRow, wallpaperBlurRow, listWallpaperRow, resetStyleRow;
    private int wallpaperPickerRow;
    private int iconsRow;

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.AxAppearance);
    }

    @Override
    protected String getKey() {
        return "appearance";
    }

    @Override
    protected void updateRows() {
        super.updateRows();
        headerRow = addRow("glass");
        lightRow = addRow("light");
        darkRow = addRow("dark");
        systemRow = addRow("system");
        navigationRow = addRow("navigation");
        iconsRow = addRow("icons");
        accentRow = addRow("accent");
        glassBlurRow = addRow("glassBlur");
        transparencyRow = addRow("transparency");
        wallpaperBlurRow = addRow("wallpaperBlur");
        listWallpaperRow = addRow("listWallpaper");
        wallpaperPickerRow = addRow("wallpaperPicker");
        infoRow = addRow("info");
        dividerRow = addRow();
        resetStyleRow = addRow("resetStyle");
        restoreRow = addRow("restore");
        telegramRow = addRow("telegram");
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == lightRow || position == darkRow || position == systemRow) {
            AxAppearance.applyMode(position == lightRow ? AxAppearance.MODE_LIGHT
                    : position == darkRow ? AxAppearance.MODE_DARK : AxAppearance.MODE_SYSTEM);
        } else if (position == navigationRow) {
            AxAppearance.setGlassNavigation(!AxAppearance.useGlassNavigation());
        } else if (position == iconsRow) {
            one.axgram.core.appearance.AxIconPack.setEnabled(!one.axgram.core.appearance.AxIconPack.enabled());
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(one.axgram.core.appearance.AxIconPack.enabled());
            return;
        } else if (position == accentRow) {
            showAccentPicker();
        } else if (position == glassBlurRow) {
            AxAppearance.setGlassBlur(!AxAppearance.glassBlurEnabled());
            invalidateMaterials(fragmentView.getRootView());
            // Keep the attached switch: replacing its holder interrupts the native animation.
            syncPanelControls(view, layoutManager.findViewByPosition(transparencyRow), AxAppearance.glassBlurEnabled());
            return;
        } else if (position == transparencyRow || position == wallpaperBlurRow) {
            if (position == transparencyRow && (!AxAppearance.isGlassTheme() || !AxAppearance.glassBlurEnabled())) return;
            showPercentPicker(position == wallpaperBlurRow);
        } else if (position == listWallpaperRow) {
            AxAppearance.setListWallpaperVisible(!AxAppearance.listWallpaperVisible());
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(AxAppearance.listWallpaperVisible());
            invalidateMaterials(fragmentView.getRootView());
            return;
        } else if (position == wallpaperPickerRow) {
            presentFragment(new WallpapersListActivity(WallpapersListActivity.TYPE_ALL));
        } else if (position == resetStyleRow) {
            AxAppearance.resetStyle();
        } else if (position == restoreRow) {
            AxAppearance.restorePreviousTheme();
        } else if (position == telegramRow) {
            presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC));
        }
        if (listAdapter != null) listAdapter.notifyDataSetChanged();
    }

    private void showAccentPicker() {
        if (getParentActivity() == null) return;
        int initial = AxAppearance.accentColor();
        final int[] selected = {initial == 0 ? Theme.getColor(Theme.key_glass_tabSelected) : initial};
        ColorPicker picker = new ColorPicker(getParentActivity(), false, (color, num, applyNow) -> selected[0] = color | 0xff000000) {
            @Override protected void onMeasure(int width, int height) {
                super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(300), View.MeasureSpec.EXACTLY));
            }
        };
        picker.setColor(selected[0], 0);
        picker.setType(-1, true, 1, 1, false, 0, false);
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.AxAppearanceAccent)).setView(picker)
                .setPositiveButton(LocaleController.getString(R.string.AxAppearanceApply), (dialog, which) -> {
                    AxAppearance.saveStyle("accent_color", selected[0]); refreshRows();
                })
                .setNeutralButton(LocaleController.getString(R.string.AxAppearanceDefault), (dialog, which) -> {
                    AxAppearance.saveStyle("accent_color", 0); refreshRows();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null).create());
    }

    private void showPercentPicker(boolean wallpaper) {
        if (getParentActivity() == null) return;
        int max = wallpaper ? 100 : 80;
        final int[] value = {wallpaper ? AxAppearance.wallpaperBlur() : AxAppearance.glassTransparency()};
        FrameLayout content = new FrameLayout(getParentActivity());
        TextView label = new TextView(getParentActivity());
        label.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        label.setTextSize(18); label.setGravity(Gravity.CENTER); label.setText(value[0] + "%");
        content.addView(label, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, Gravity.TOP));
        SeekBarView bar = new SeekBarView(getParentActivity());
        bar.setReportChanges(true);
        bar.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override public void onSeekBarDrag(boolean stop, float progress) {
                value[0] = Math.round(progress * max); label.setText(value[0] + "%");
            }
            @Override public void onSeekBarPressed(boolean pressed) {}
        });
        bar.setProgress(value[0] / (float) max);
        content.addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP, 20, 42, 20, 16));
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(wallpaper ? R.string.AxAppearanceWallpaperBlur : R.string.AxAppearanceTransparency))
                .setView(content).setPositiveButton(LocaleController.getString(R.string.AxAppearanceApply), (dialog, which) -> {
                    AxAppearance.saveStyle(wallpaper ? "wallpaper_blur" : "glass_transparency", value[0]); refreshRows();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null).create());
    }

    private void refreshRows() { if (listAdapter != null) listAdapter.notifyDataSetChanged(); }

    private static void syncPanelControls(View toggle, View percentage, boolean enabled) {
        if (toggle instanceof TextCheckCell) ((TextCheckCell) toggle).setChecked(enabled);
        if (percentage != null) {
            percentage.setAlpha(1f);
            percentage.setEnabled(enabled);
        }
    }

    private static void invalidateMaterials(View view) {
        if (view.getBackground() instanceof org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable) {
            ((org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable) view.getBackground()).updateColors();
        }
        view.invalidate();
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) invalidateMaterials(group.getChildAt(i));
        }
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new BaseListAdapter(context) {
            @Override
            public int getItemViewType(int position) {
                if (position == headerRow) return TYPE_HEADER;
                if (position == lightRow || position == darkRow || position == systemRow) return TYPE_RADIO;
                if (position == navigationRow || position == glassBlurRow || position == listWallpaperRow || position == iconsRow) return TYPE_CHECK;
                if (position == infoRow) return TYPE_INFO_PRIVACY;
                if (position == dividerRow) return TYPE_SHADOW;
                return TYPE_SETTINGS;
            }

            @Override
            public boolean isEnabled(@NonNull RecyclerView.ViewHolder holder) {
                int position = holder.getAdapterPosition();
                if (position == restoreRow) return AxAppearance.hasPreviousTheme();
                if (position == accentRow || position == glassBlurRow || position == wallpaperBlurRow || position == listWallpaperRow || position == resetStyleRow) return AxAppearance.isGlassTheme();
                if (position == transparencyRow) return AxAppearance.isGlassTheme() && AxAppearance.glassBlurEnabled();
                return position != headerRow && position != infoRow && position != dividerRow;
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial, boolean divider) {
                holder.itemView.setEnabled(isEnabled(holder));
                holder.itemView.setAlpha(position == transparencyRow && (!AxAppearance.isGlassTheme() || !AxAppearance.glassBlurEnabled())
                        || (position == accentRow || position == glassBlurRow || position == wallpaperBlurRow || position == listWallpaperRow || position == resetStyleRow) && !AxAppearance.isGlassTheme() ? .5f : 1f);
                if (position == headerRow) {
                    ((HeaderCell) holder.itemView).setText("AxGram Glass");
                } else if (position == lightRow || position == darkRow || position == systemRow) {
                    int mode = position == lightRow ? AxAppearance.MODE_LIGHT
                            : position == darkRow ? AxAppearance.MODE_DARK : AxAppearance.MODE_SYSTEM;
                    int title = mode == AxAppearance.MODE_LIGHT ? R.string.AxAppearanceLight
                            : mode == AxAppearance.MODE_DARK ? R.string.AxAppearanceDark : R.string.AxAppearanceSystem;
                    ((TextRadioCell) holder.itemView).setTextAndCheck(LocaleController.getString(title),
                            AxAppearance.isGlassTheme() && AxAppearance.mode() == mode, true);
                } else if (position == navigationRow) {
                    ((TextCheckCell) holder.itemView).setTextAndCheck(LocaleController.getString(R.string.AxAppearanceNavigation),
                            AxAppearance.useGlassNavigation(), false);
                } else if (position == iconsRow) {
                    ((TextCheckCell) holder.itemView).setTextAndCheck(LocaleController.getString(R.string.AxAppearanceIcons),
                            one.axgram.core.appearance.AxIconPack.enabled(), true);
                } else if (position == infoRow) {
                    ((TextInfoPrivacyCell) holder.itemView).setText(LocaleController.getString(R.string.AxAppearanceEffectsInfo));
                } else if (position == accentRow) {
                    int color = AxAppearance.accentColor();
                    ((TextSettingsCell) holder.itemView).setTextAndValue(LocaleController.getString(R.string.AxAppearanceAccent),
                            color == 0 ? LocaleController.getString(R.string.AxAppearanceDefault) : String.format(java.util.Locale.US, "#%06X", color & 0xffffff), true);
                } else if (position == glassBlurRow) {
                    ((TextCheckCell) holder.itemView).setTextAndCheck(LocaleController.getString(R.string.AxAppearanceGlassBlur), AxAppearance.glassBlurEnabled(), true);
                } else if (position == listWallpaperRow) {
                    ((TextCheckCell) holder.itemView).setTextAndCheck(LocaleController.getString(R.string.AxAppearanceListWallpaper), AxAppearance.listWallpaperVisible(), false);
                } else if (position == transparencyRow || position == wallpaperBlurRow) {
                    if (position == transparencyRow) holder.itemView.setAlpha(1f);
                    ((TextSettingsCell) holder.itemView).setTextAndValue(LocaleController.getString(position == transparencyRow ? R.string.AxAppearanceTransparency : R.string.AxAppearanceWallpaperBlur),
                            (position == transparencyRow ? AxAppearance.glassTransparency() : AxAppearance.wallpaperBlur()) + "%", true);
                } else if (position == resetStyleRow) {
                    ((TextSettingsCell) holder.itemView).setText(LocaleController.getString(R.string.AxAppearanceResetStyle), true);
                } else if (position == restoreRow) {
                    ((TextSettingsCell) holder.itemView).setText(LocaleController.getString(R.string.AxAppearanceRestore), true);
                    holder.itemView.setAlpha(AxAppearance.hasPreviousTheme() ? 1f : .5f);
                } else if (position == wallpaperPickerRow) {
                    ((TextSettingsCell) holder.itemView).setText(LocaleController.getString(R.string.ChangeChatBackground), false);
                } else if (position == telegramRow) {
                    ((TextSettingsCell) holder.itemView).setText(LocaleController.getString(R.string.AxAppearanceTelegram), false);
                    holder.itemView.setAlpha(1f);
                }
            }
        };
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = super.getThemeDescriptions();
        descriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextRadioCell.class, TextCheckCell.class, TextSettingsCell.class},
                new String[]{"textView", "textView", "textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextSettingsCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CHECKBOX, new Class[]{TextRadioCell.class}, new String[]{"radioButton"}, null, null, null, Theme.key_radioBackground));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CHECKBOXCHECK, new Class[]{TextRadioCell.class}, new String[]{"radioButton"}, null, null, null, Theme.key_radioBackgroundChecked));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrack));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrackChecked));
        descriptions.add(new ThemeDescription(null, 0, null, null, null, () -> {
            if (listView != null) listView.invalidate();
        }, Theme.key_windowBackgroundWhite));
        return descriptions;
    }
}
