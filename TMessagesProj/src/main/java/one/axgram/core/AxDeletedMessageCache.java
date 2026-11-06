package one.axgram.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

final class AxDeletedMessageCache<T> {
    private static final class AccountMessages<T> {
        final long ownerId;
        final HashMap<Long, LinkedHashMap<Integer, T>> dialogs = new HashMap<>();

        AccountMessages(long ownerId) {
            this.ownerId = ownerId;
        }
    }

    private final HashMap<Integer, AccountMessages<T>> accounts = new HashMap<>();

    private AccountMessages<T> account(int account, long ownerId, boolean create) {
        AccountMessages<T> messages = accounts.get(account);
        if (messages != null && messages.ownerId != ownerId) {
            accounts.remove(account);
            messages = null;
        }
        if (ownerId <= 0) {
            return null;
        }
        if (messages == null && create) {
            messages = new AccountMessages<>(ownerId);
            accounts.put(account, messages);
        }
        return messages;
    }

    synchronized void put(int account, long ownerId, long dialogId, int messageId, T value) {
        AccountMessages<T> messages = account(account, ownerId, true);
        if (messages != null && value != null) {
            messages.dialogs.computeIfAbsent(dialogId, ignored -> new LinkedHashMap<>())
                    .put(messageId, value);
        }
    }

    synchronized ArrayList<T> get(int account, long ownerId, long dialogId) {
        AccountMessages<T> messages = account(account, ownerId, false);
        LinkedHashMap<Integer, T> dialog = messages == null ? null : messages.dialogs.get(dialogId);
        return dialog == null ? new ArrayList<>() : new ArrayList<>(dialog.values());
    }

    synchronized void remove(int account, long ownerId, long dialogId, int messageId) {
        AccountMessages<T> messages = account(account, ownerId, false);
        if (messages == null) {
            return;
        }
        LinkedHashMap<Integer, T> dialog = messages.dialogs.get(dialogId);
        if (dialog != null) {
            dialog.remove(messageId);
            if (dialog.isEmpty()) {
                messages.dialogs.remove(dialogId);
            }
        }
    }

    synchronized void clearDialog(int account, long ownerId, long dialogId) {
        AccountMessages<T> messages = account(account, ownerId, false);
        if (messages != null) {
            messages.dialogs.remove(dialogId);
        }
    }

    synchronized void clearAccount(int account) {
        accounts.remove(account);
    }
}
