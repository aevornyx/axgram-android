package one.axgram.core.settings;

import android.content.Context;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import one.axgram.core.folders.AxFolderAuthClient;
import one.axgram.core.folders.AxFolderSession;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import java.text.DateFormat;
import java.util.Date;

public final class AxFolderSyncDevicesActivity extends BaseAxSettingsActivity {
    private int header, statusRow, refresh, firstDevice, info;
    private AxFolderSession session;
    private AxFolderAuthClient.Devices devices;
    private boolean busy, destroyed;
    private String status = "Загружаем устройства…";

    public AxFolderSyncDevicesActivity(int account) { super(); currentAccount = account; }

    @Override public View createView(Context context) {
        session = AxFolderSession.get(getAccountInstance());
        return super.createView(context);
    }

    @Override public void onResume() { super.onResume(); load(); }
    @Override public void onFragmentDestroy() { destroyed = true; super.onFragmentDestroy(); }
    @Override protected String getActionBarTitle() { return "Устройства синхронизации"; }
    @Override protected String getKey() { return null; }

    @Override protected void updateRows() {
        super.updateRows();
        header = addRow(); statusRow = addRow(); refresh = addRow(); firstDevice = rowCount;
        if (devices != null) for (var ignored : devices.devices) addRow();
        info = addRow();
    }

    private void changed() { updateRows(); if (listAdapter != null) listAdapter.notifyDataSetChanged(); }

    private void load() {
        if (session == null || busy || destroyed) return;
        busy = true; status = "Загружаем устройства…"; changed();
        session.devices((result, loginRequired) -> {
            if (destroyed) return;
            busy = false; devices = result;
            status = result != null ? "Выберите устройство, чтобы отключить его синхронизацию."
                    : loginRequired ? "Подтвердите подключение снова в настройках синхронизации."
                    : "Не удалось загрузить устройства. Проверьте соединение и повторите попытку.";
            changed();
        });
    }

    @Override protected void onItemClick(View view, int position, float x, float y) {
        if (busy) return;
        if (position == refresh) { load(); return; }
        if (devices == null || position < firstDevice || position >= info || getParentActivity() == null) return;
        var device = devices.devices.get(position - firstDevice);
        boolean self = devices.current.equals(device.id);
        if (!devices.canManage && !self) return;
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle("Отключить синхронизацию?")
                .setMessage(device.label + " перестанет получать и отправлять изменения папок. Сохранённые папки останутся на устройстве. Для подключения снова понадобится подтверждение Telegram.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Отключить", (dialog, which) -> {
                    busy = true; status = "Отключаем устройство…"; changed();
                    session.revokeDevice(device.id, ok -> {
                        if (destroyed) return;
                        busy = false;
                        if (ok && !self) load();
                        else {
                            if (ok) devices = null;
                            status = ok ? "Синхронизация на этом устройстве отключена. Папки сохранены."
                                    : "Не удалось отключить устройство. Обновите список и повторите попытку.";
                            changed();
                        }
                    });
                }).create());
    }

    @Override protected BaseListAdapter createAdapter(Context context) { return new Adapter(context); }

    private final class Adapter extends BaseListAdapter {
        Adapter(Context context) { super(context); }
        @Override public int getItemViewType(int position) {
            if (position == header) return TYPE_HEADER;
            if (position == statusRow || position == info) return TYPE_INFO_PRIVACY;
            return position == refresh ? TYPE_SETTINGS : TYPE_DETAIL_SETTINGS;
        }
        @Override public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return !busy && (position == refresh || devices != null && position >= firstDevice && position < info
                    && (devices.canManage || devices.current.equals(devices.devices.get(position - firstDevice).id)));
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial, boolean divider) {
            if (position == header) ((HeaderCell) holder.itemView).setText("Подключения к сервису AxGram");
            else if (position == statusRow) ((TextInfoPrivacyCell) holder.itemView).setText(status);
            else if (position == info) ((TextInfoPrivacyCell) holder.itemView).setText("Здесь управляется доступ к синхронизации папок. Отзыв не завершает сеанс Telegram и не удаляет уже сохранённые папки.");
            else if (position == refresh) ((TextSettingsCell) holder.itemView).setText("Обновить список", true);
            else {
                var device = devices.devices.get(position - firstDevice);
                String title = device.label + (devices.current.equals(device.id) ? " · Это устройство" : "");
                String expiry = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(device.expires * 1000));
                ((TextDetailSettingsCell) holder.itemView).setTextAndValue(title, "Доступ до " + expiry, position + 1 < info);
            }
        }
    }
}
