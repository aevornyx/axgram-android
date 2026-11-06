package one.axgram.core.folders;

public final class AxFolderArchiveRules {
    private AxFolderArchiveRules() { }

    public static boolean matches(boolean onlyArchived, boolean excludeArchived, boolean archived) {
        return (!onlyArchived && !excludeArchived)
                || (onlyArchived != excludeArchived && onlyArchived == archived);
    }
}
