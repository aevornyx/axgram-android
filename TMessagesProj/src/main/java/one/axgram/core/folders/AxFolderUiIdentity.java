package one.axgram.core.folders;

import org.json.JSONException;
import org.telegram.messenger.MessagesController;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Recovers an omitted UI identity only when persisted ID and all folder settings agree. */
public final class AxFolderUiIdentity {
    private AxFolderUiIdentity() { }

    public static int restoreMissing(AxFolderReplica state, List<MessagesController.DialogFilter> filters,
            AxFolderFilterCodec.PeerTypes types) throws JSONException {
        HashMap<Integer, MessagesController.DialogFilter> stored = new HashMap<>();
        for (MessagesController.DialogFilter filter : state.nativeFolders()) stored.put(filter.id, filter);
        HashSet<String> identities = new HashSet<>();
        for (MessagesController.DialogFilter filter : filters) {
            if (filter.id >= 1000 && filter.axSyncUuid != null && !identities.add(filter.axSyncUuid)) throw new JSONException("Duplicate native folder UUID");
        }
        ArrayList<MessagesController.DialogFilter> missing = new ArrayList<>();
        for (MessagesController.DialogFilter filter : filters) {
            if (filter.id < 1000 || filter.axSyncUuid != null) continue;
            MessagesController.DialogFilter persisted = stored.get(filter.id);
            if (persisted == null || !identities.add(persisted.axSyncUuid)
                    || !AxFolderDocument.canonical(AxFolderFilterCodec.exportFilter(filter, types)).equals(
                        AxFolderDocument.canonical(AxFolderFilterCodec.exportFilter(persisted, types)))
                    || !AxFolderLocalRules.same(AxFolderLocalRules.capture(filter), AxFolderLocalRules.capture(persisted))) {
                throw new JSONException("Unidentified local folder differs from persisted state");
            }
            missing.add(filter);
        }
        for (MessagesController.DialogFilter filter : missing) filter.axSyncUuid = stored.get(filter.id).axSyncUuid;
        return missing.size();
    }
}
