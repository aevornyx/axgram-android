package one.axgram.core;

import one.axgram.core.folders.AxFolderArchiveRules;
import org.junit.Test;
import static org.junit.Assert.*;

public class AxFolderArchiveRulesTest {
    @Test public void archiveRuleTruthTable() {
        for (boolean archived : new boolean[] {false, true}) {
            assertTrue(AxFolderArchiveRules.matches(false, false, archived));
            assertEquals(archived, AxFolderArchiveRules.matches(true, false, archived));
            assertEquals(!archived, AxFolderArchiveRules.matches(false, true, archived));
            assertFalse(AxFolderArchiveRules.matches(true, true, archived));
        }
    }
}
