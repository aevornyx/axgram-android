package one.axgram.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AxDeletedMediaPolicyTest {
    @Test
    public void exactLimitIsAllowedButOverflowIsBlocked() {
        assertEquals(AxArchivePolicy.SAVED, AxArchivePolicy.check(7, 3, 100, 10, 0));
        assertEquals(AxArchivePolicy.LIMIT_REACHED, AxArchivePolicy.check(7, 4, 100, 10, 0));
        assertEquals(AxArchivePolicy.LIMIT_REACHED,
                AxArchivePolicy.check(Long.MAX_VALUE - 2, 3, Long.MAX_VALUE, Long.MAX_VALUE, 0));
    }

    @Test
    public void disablingMediaAndLoweringLimitNeverRequestsOldFileDeletion() {
        assertEquals(AxArchivePolicy.DISABLED, AxArchivePolicy.check(100, 1, 1000, 0, 0));
        assertEquals(AxArchivePolicy.LIMIT_REACHED, AxArchivePolicy.check(100, 1, 1000, 50, 0));
    }

    @Test
    public void unlimitedStillPreservesFreeSpaceReserve() {
        assertEquals(AxArchivePolicy.SAVED, AxArchivePolicy.check(10000, 10, 110, -1, 100));
        assertEquals(AxArchivePolicy.LOW_SPACE, AxArchivePolicy.check(10000, 11, 110, -1, 100));
        assertEquals(AxArchivePolicy.LOW_SPACE, AxArchivePolicy.check(0, 0, 99, -1, 100));
    }

    @Test
    public void invalidCountersFailClosed() {
        assertEquals(AxArchivePolicy.ERROR, AxArchivePolicy.check(-1, 10, 100, -1, 0));
        assertEquals(AxArchivePolicy.ERROR, AxArchivePolicy.check(0, -1, 100, -1, 0));
        assertEquals(AxArchivePolicy.ERROR, AxArchivePolicy.check(0, 10, 100, -2, 0));
    }
}
