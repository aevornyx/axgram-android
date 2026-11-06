package one.axgram.core.folders;

import org.json.JSONException;
import java.io.IOException;

public interface AxFolderStateStore {
    AxFolderReplica load() throws IOException, JSONException;
    boolean compareAndSave(AxFolderReplica expected, AxFolderReplica updated) throws IOException, JSONException;
}
