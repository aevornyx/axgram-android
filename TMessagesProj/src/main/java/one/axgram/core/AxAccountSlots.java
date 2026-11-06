package one.axgram.core;

import java.util.function.IntPredicate;

public final class AxAccountSlots {
    public static final int CAPACITY = 128;

    private AxAccountSlots() {
    }

    public static int findAvailableSlot(IntPredicate isActivated) {
        for (int account = 0; account < CAPACITY; account++) {
            if (!isActivated.test(account)) {
                return account;
            }
        }
        return -1;
    }
}
