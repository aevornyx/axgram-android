package one.axgram.core.appearance;

import org.junit.Test;
import static org.junit.Assert.*;

public class AxGlassNavigationGeometryTest {
    @Test public void keepsSelectedCircleInsideBothEdges() {
        assertEquals(32f, AxGlassNavigationGeometry.clampCenter(-50, 8, 352, 24), 0);
        assertEquals(328f, AxGlassNavigationGeometry.clampCenter(500, 8, 352, 24), 0);
        assertEquals(180f, AxGlassNavigationGeometry.clampCenter(180, 8, 352, 24), 0);
    }
    @Test public void narrowWindowAndMissingSelectionStayCentered() {
        assertEquals(20f, AxGlassNavigationGeometry.clampCenter(1, 8, 32, 24), 0);
        assertEquals(180f, AxGlassNavigationGeometry.clampCenter(Float.NaN, 8, 352, 24), 0);
        assertEquals(180f, AxGlassNavigationGeometry.clampCenter(Float.POSITIVE_INFINITY, 8, 352, 24), 0);
    }
    @Test public void actualTabCoordinatesWorkInEitherDirection() {
        for (float x : new float[]{48, 136, 224, 312, 312, 224, 136, 48}) {
            assertEquals(x, AxGlassNavigationGeometry.clampCenter(x, 8, 352, 24), 0);
        }
    }
}
