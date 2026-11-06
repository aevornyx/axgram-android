package one.axgram.core;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

public final class AxMessageHistoryActivity extends BaseFragment {
    private static final DispatchQueue queue = new DispatchQueue("AxMessageHistoryView");
    private final long ownerId;
    private final long dialogId;
    private final int messageId;
    private final boolean protectedContent;
    private final ArrayList<AxMessageHistory.Version> versions = new ArrayList<>();
    private RecyclerListView list;
    private RecyclerListView.SelectionAdapter adapter;
    private TextView empty;
    private boolean loading;
    private boolean more = true;
    private volatile int generation;

    public AxMessageHistoryActivity(MessageObject message) {
        currentAccount = message.currentAccount;
        ownerId = getUserConfig().getClientUserId();
        dialogId = message.getDialogId();
        messageId = message.getId();
        TLRPC.Chat chat = dialogId < 0 ? getMessagesController().getChat(-dialogId) : null;
        protectedContent = message.messageOwner.noforwards || (chat != null && chat.noforwards);
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle("История изменений");
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else if (id == 1) confirmClear();
            }
        });
        actionBar.createMenu().addItem(1, R.drawable.msg_delete);
        FrameLayout frame = new FrameLayout(context);
        fragmentView = frame;
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        list = new RecyclerListView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        adapter = new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return holder.getItemViewType() == 1 || !protectedContent;
            }

            @Override
            public int getItemCount() {
                return versions.size() + (more && !versions.isEmpty() ? 1 : 0);
            }

            @Override
            public int getItemViewType(int position) {
                return position == versions.size() ? 1 : 0;
            }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(12), AndroidUtilities.dp(20), AndroidUtilities.dp(12));
                row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                TextView title = new TextView(context);
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                row.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                TextView text = new TextView(context);
                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                row.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
                return new RecyclerListView.Holder(row);
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                LinearLayout row = (LinearLayout) holder.itemView;
                TextView title = (TextView) row.getChildAt(0);
                TextView text = (TextView) row.getChildAt(1);
                if (position == versions.size()) {
                    title.setText(loading ? "Загрузка…" : "Показать более ранние версии");
                    text.setText("");
                    return;
                }
                AxMessageHistory.Version version = versions.get(position);
                title.setText(LocaleController.formatDateTime(version.date, true)
                        + " · получено " + LocaleController.formatDateTime(version.observedAt, true));
                if (version.message == null) {
                    text.setText("Не удалось прочитать эту версию");
                } else {
                    String value = version.message.message;
                    SpannableStringBuilder formatted = new SpannableStringBuilder(value == null || value.isEmpty() ? "[Без текста]" : value);
                    MessageObject.addEntitiesToText(formatted, version.message.entities, version.message.out, false, false, false);
                    text.setText(formatted);
                }
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((view, position) -> {
            if (position == versions.size()) {
                load();
            } else if (!protectedContent && position < versions.size()) {
                AxMessageHistory.Version version = versions.get(position);
                if (ownerId == getUserConfig().getClientUserId() && version.message != null
                        && !version.message.noforwards && version.message.message != null && !version.message.message.isEmpty()) {
                    AndroidUtilities.addToClipboard(version.message.message);
                    BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                }
            }
        });
        frame.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        empty = new TextView(context);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24));
        empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        empty.setText("Загрузка истории…");
        frame.addView(empty, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        list.setEmptyView(empty);
        load();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (ownerId != getUserConfig().getClientUserId()) finishFragment();
    }

    private void load() {
        if (loading || !more || ownerId <= 0) return;
        if (ownerId != getUserConfig().getClientUserId()) {
            finishFragment();
            return;
        }
        loading = true;
        final int request = generation;
        final long before = versions.isEmpty() ? Long.MAX_VALUE : versions.get(versions.size() - 1).id;
        queue.postRunnable(() -> {
            ArrayList<AxMessageHistory.Version> page = AxMessageHistory.getInstance().getVersions(ownerId, dialogId, messageId, before);
            AndroidUtilities.runOnUIThread(() -> {
                if (request != generation || getParentActivity() == null || ownerId != getUserConfig().getClientUserId()) return;
                loading = false;
                more = page.size() == 50;
                versions.addAll(page);
                empty.setText("Сохранённых версий пока нет.\n\nИстория появится, когда AxGram получит изменение уже известного сообщения. Правки, которые клиент не видел, получить задним числом нельзя.");
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void confirmClear() {
        if (getParentActivity() == null || ownerId != getUserConfig().getClientUserId()) return;
        AlertDialog.Builder dialog = new AlertDialog.Builder(getParentActivity());
        dialog.setTitle("Удалить историю изменений?");
        dialog.setMessage("Сохранённые версии этого сообщения будут удалены только на этом устройстве. Само сообщение и архив удалённых останутся.");
        dialog.setPositiveButton("Удалить", (d, which) -> {
            final int request = ++generation;
            queue.postRunnable(() -> {
                AxMessageHistory.getInstance().clear(ownerId, dialogId, messageId);
                AndroidUtilities.runOnUIThread(() -> {
                    if (request == generation && getParentActivity() != null) finishFragment();
                });
            });
        });
        dialog.setNegativeButton("Отмена", null);
        showDialog(dialog.create());
    }

    @Override
    public void onFragmentDestroy() {
        generation++;
        super.onFragmentDestroy();
    }
}
