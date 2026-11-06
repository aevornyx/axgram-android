package one.axgram.core;

import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.*;

public class AxAccountSlotsTest {
    @Test
    public void allowsEverySlotIncludingThoseAboveFormerPremiumLimits() {
        boolean[] active = new boolean[AxAccountSlots.CAPACITY];
        for (int expected = 0; expected < active.length; expected++) {
            int slot = AxAccountSlots.findAvailableSlot(i -> active[i]);
            assertEquals(expected, slot);
            active[slot] = true;
        }
        assertEquals(-1, AxAccountSlots.findAvailableSlot(i -> active[i]));
    }

    @Test
    public void reusesAnyFreedSlotEvenWhenAllOthersAreOccupied() {
        for (int freed = 0; freed < AxAccountSlots.CAPACITY; freed++) {
            final int freeSlot = freed;
            assertEquals(freed, AxAccountSlots.findAvailableSlot(i -> i != freeSlot));
        }
    }

    @Test
    public void neverQueriesOutsideNativeAccountArray() {
        int[] visited = {0};
        assertEquals(-1, AxAccountSlots.findAvailableSlot(i -> {
            assertTrue(i >= 0 && i < 128);
            visited[0]++;
            return true;
        }));
        assertEquals(128, visited[0]);
    }

    @Test
    public void stopsAtFirstFreeSlot() {
        assertEquals(9, AxAccountSlots.findAvailableSlot(i -> {
            assertTrue(i <= 9);
            return i < 9;
        }));
    }

    @Test
    public void repeatedLogoutAndLoginPreserveSlotOwnership() {
        boolean[] active = new boolean[AxAccountSlots.CAPACITY];
        Random random = new Random(20261027);
        for (int step = 0; step < 20000; step++) {
            if (random.nextInt(4) == 0) {
                active[random.nextInt(active.length)] = false;
            } else {
                int slot = AxAccountSlots.findAvailableSlot(i -> active[i]);
                if (slot >= 0) {
                    assertFalse(active[slot]);
                    active[slot] = true;
                } else {
                    for (boolean occupied : active) assertTrue(occupied);
                }
            }
        }
    }
}
