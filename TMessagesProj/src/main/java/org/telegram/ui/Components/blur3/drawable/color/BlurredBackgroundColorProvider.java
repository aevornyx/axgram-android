package org.telegram.ui.Components.blur3.drawable.color;

import androidx.annotation.ColorInt;

public interface BlurredBackgroundColorProvider {
    default boolean usesAxAppearanceControls() { return false; }
    @ColorInt int getShadowColor();
    @ColorInt int getBackgroundColor();
    @ColorInt int getStrokeColorTop();
    @ColorInt int getStrokeColorBottom();
}
