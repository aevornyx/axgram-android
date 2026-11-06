package one.axgram.core.settings;

import android.content.Context;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import one.axgram.core.folders.AxFolderSession;

public final class AxFolderSyncSettingsActivity extends BaseAxSettingsActivity {
    private int header, statusRow, connect, retry, disconnect, devices, info;
    private AxFolderSession session;
    private final Runnable changed = () -> { if (listAdapter != null) listAdapter.notifyDataSetChanged(); };

    @Override public View createView(Context context) {
        session = AxFolderSession.get(getAccountInstance());
        return super.createView(context);
    }

    @Override public void onResume() {
        super.onResume();
        if (session != null) { session.subscribe(changed); session.restoreWhenReady(); changed.run(); }
    }

    @Override public void onPause() { if (session != null) session.unsubscribe(changed); super.onPause(); }
    @Override public void onFragmentDestroy() { if (session != null) session.unsubscribe(changed); super.onFragmentDestroy(); }
    @Override protected String getActionBarTitle() { return "Синхронизация папок"; }
    @Override protected String getKey() { return null; }

    @Override protected void updateRows() {
        super.updateRows();
        header = addRow(); statusRow = addRow(); connect = addRow(); retry = addRow(); disconnect = addRow(); devices = addRow(); info = addRow();
    }

    @Override protected void onItemClick(View view, int position, float x, float y) {
        if (session == null) return;
        if (position == connect) session.connect();
        else if (position == retry) session.retry();
        else if (position == disconnect) session.disconnect();
        else if (position == devices) presentFragment(new AxFolderSyncDevicesActivity(currentAccount));
    }

    private String status() {
        if (session == null) return "Загрузка";
        switch (session.state()) {
            case RESTORING: return "Восстанавливаем подключение";
            case SIGNING_IN: return "Подтвердите подключение в AxGram";
            case STARTING: return "Подключаем папки";
            case SYNCING: return "Синхронизация";
            case READY: return "Подключено";
            case RETRYING: return "Повторим при восстановлении связи";
            case LOGIN_REQUIRED: return "Подтвердите подключение снова";
            case ERROR: return "Не удалось подключиться. Повторите попытку";
            case CONFIRMATION_EXPIRED: return "Подтверждение устарело";
            default: return "Выключено";
        }
    }

    @Override protected BaseListAdapter createAdapter(Context context) { return new Adapter(context); }

    private final class Adapter extends BaseListAdapter {
        Adapter(Context context) { super(context); }
        @Override public int getItemViewType(int position) {
            return position == header ? TYPE_HEADER : position == info ? TYPE_INFO_PRIVACY : TYPE_SETTINGS;
        }
        @Override public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            if (session == null) return false;
            AxFolderSession.State state = session.state();
            if (position == disconnect) return state != AxFolderSession.State.OFF;
            if (position == devices) return state == AxFolderSession.State.READY || state == AxFolderSession.State.SYNCING || state == AxFolderSession.State.RETRYING;
            boolean idle = state != AxFolderSession.State.SIGNING_IN && state != AxFolderSession.State.RESTORING && state != AxFolderSession.State.STARTING;
            return idle && (position == connect && (state == AxFolderSession.State.OFF || state == AxFolderSession.State.ERROR || state == AxFolderSession.State.LOGIN_REQUIRED)
                    || position == retry && (state == AxFolderSession.State.ERROR || state == AxFolderSession.State.RETRYING || state == AxFolderSession.State.CONFIRMATION_EXPIRED));
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial, boolean divider) {
            if (position == header) ((HeaderCell) holder.itemView).setText("Дополнительные папки AxGram");
            else if (position == info) ((TextInfoPrivacyCell) holder.itemView).setText(
                    (session != null && session.state() == AxFolderSession.State.CONFIRMATION_EXPIRED
                            ? "Срок подтверждения Telegram истёк. Получите новое подтверждение и попробуйте снова. Ваши папки сохранены.\n\n" : "")
                    + "Используйте один и тот же аккаунт Telegram на всех устройствах AxGram и подтвердите подключение в приложении. Названия, оформление, состав и порядок дополнительных папок будут общими. "
                    + "Секретные чаты в составе и закреплениях папок остаются только на этом устройстве. "
                    + "Сообщения и сессия Telegram в сервис AxGram не передаются. Сервис хранит настройки папок и технически может их прочитать. "
                    + "Отключение на этом устройстве сохраняет сами папки; для подключения снова потребуется подтверждение.");
            else {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                if (position == statusRow) cell.setTextAndValue("Состояние", status(), true);
                else if (position == connect) cell.setText("Включить синхронизацию", true);
                else if (position == retry) cell.setText(session != null && session.state() == AxFolderSession.State.CONFIRMATION_EXPIRED
                        ? "Получить новое подтверждение" : "Повторить синхронизацию", true);
                else if (position == devices) cell.setText("Устройства синхронизации", false);
                else cell.setText("Отключить на этом устройстве", true);
            }
        }
    }
}
