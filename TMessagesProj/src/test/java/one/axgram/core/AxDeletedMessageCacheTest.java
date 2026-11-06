package one.axgram.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AxDeletedMessageCacheTest {
    @Test
    public void sharedDialogAndMessageIdsStayInTheirOwnAccounts() {
        AxDeletedMessageCache<String> cache = new AxDeletedMessageCache<>();
        cache.put(0, 1001, -55, 7, "first account");
        cache.put(1, 2002, -55, 7, "second account");

        assertEquals(Collections.singletonList("first account"), cache.get(0, 1001, -55));
        assertEquals(Collections.singletonList("second account"), cache.get(1, 2002, -55));
        cache.remove(0, 1001, -55, 7);
        assertTrue(cache.get(0, 1001, -55).isEmpty());
        assertEquals(Collections.singletonList("second account"), cache.get(1, 2002, -55));
    }

    @Test
    public void reusingAccountSlotDoesNotExposePreviousOwner() {
        AxDeletedMessageCache<String> cache = new AxDeletedMessageCache<>();
        cache.put(0, 1001, 55, 7, "previous owner");

        assertTrue(cache.get(0, 3003, 55).isEmpty());
        cache.put(0, 3003, 55, 7, "new owner");
        assertEquals(Collections.singletonList("new owner"), cache.get(0, 3003, 55));
        assertTrue(cache.get(0, 1001, 55).isEmpty());
    }

    @Test
    public void logoutClearsOnlyItsAccountAndRejectsUnauthenticatedReads() {
        AxDeletedMessageCache<String> cache = new AxDeletedMessageCache<>();
        cache.put(0, 1001, 55, 7, "first");
        cache.put(1, 2002, 55, 7, "second");
        cache.clearAccount(0);

        assertTrue(cache.get(0, 1001, 55).isEmpty());
        assertEquals(Collections.singletonList("second"), cache.get(1, 2002, 55));
        assertTrue(cache.get(1, 0, 55).isEmpty());
        assertTrue(cache.get(1, 2002, 55).isEmpty());
    }

    @Test
    public void clearingDialogKeepsOtherDialogsAndAccounts() {
        AxDeletedMessageCache<String> cache = new AxDeletedMessageCache<>();
        cache.put(0, 1001, 55, 7, "removed");
        cache.put(0, 1001, 66, 7, "other dialog");
        cache.put(1, 2002, 55, 7, "other account");
        cache.clearDialog(0, 1001, 55);

        assertTrue(cache.get(0, 1001, 55).isEmpty());
        assertEquals(Collections.singletonList("other dialog"), cache.get(0, 1001, 66));
        assertEquals(Collections.singletonList("other account"), cache.get(1, 2002, 55));
    }

    @Test
    public void snapshotsCannotMutateCacheAndRepeatedIdsAreReplaced() {
        AxDeletedMessageCache<String> cache = new AxDeletedMessageCache<>();
        cache.put(0, 1001, 55, 7, "old");
        cache.put(0, 1001, 55, 8, "next");
        cache.put(0, 1001, 55, 7, "updated");
        cache.get(0, 1001, 55).clear();

        assertEquals(Arrays.asList("updated", "next"), cache.get(0, 1001, 55));
    }
}
