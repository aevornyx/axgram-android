package one.axgram.core;

final class AxArchivePolicy {
    static final long MIB = 1024L * 1024;
    static final long DEFAULT_LIMIT = 2048 * MIB;
    static final long FREE_SPACE_RESERVE = 100 * MIB;

    static final int UNAVAILABLE = 0;
    static final int SAVING = 1;
    static final int SAVED = 2;
    static final int LIMIT_REACHED = 3;
    static final int LOW_SPACE = 4;
    static final int ERROR = 5;
    static final int DISABLED = 6;

    static int check(long used, long additional, long free, long limit, long reserve) {
        if (limit == 0) {
            return DISABLED;
        }
        if (used < 0 || additional < 0 || free < 0 || reserve < 0 || limit < -1) {
            return ERROR;
        }
        if (limit >= 0 && (used > limit || additional > limit - used)) {
            return LIMIT_REACHED;
        }
        if (free < reserve || additional > free - reserve) {
            return LOW_SPACE;
        }
        return SAVED;
    }

    private AxArchivePolicy() {
    }
}
