package one.axgram.core.appearance;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,shadows=AxIconThemeShadow.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AxGlassIconRenderTest {
    @Test public void glassLayersRemainVisibleOnBothSurfacesAndDifferentAccents() throws Exception {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        AxAppearance.preferences().edit().clear().commit();
        int oldSurface=Theme.getColor(Theme.key_windowBackgroundWhite);
        int oldAccent=Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
        Bitmap sheet=Bitmap.createBitmap(840,480,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(sheet);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        int[] resources={R.drawable.settings_account,R.drawable.filled_profile_edit_24,R.drawable.settings_folders,
            R.drawable.settings_sounds,R.drawable.settings_privacy,R.drawable.settings_calls,
            R.drawable.filled_birthday,R.drawable.filled_chatlist_mention};
        try {
            for (int row=0;row<4;row++) {
                int surface=row%2==0 ? 0xfff2f4f7 : 0xff172027;
                Theme.setColor(Theme.key_windowBackgroundWhite,surface,false);
                Theme.setColor(Theme.key_windowBackgroundWhiteBlueText,row<2 ? 0xff6bacc4 : 0xff9d80cd,false);
                paint.setColor(surface);canvas.drawRect(0,row*120,840,(row+1)*120,paint);
                for (int i=0;i<resources.length;i++) {
                    Drawable icon=AxIconPack.badge(RuntimeEnvironment.getApplication(),resources[i]);
                    int left=18+i*104,top=row*120+14;
                    icon.setBounds(left,top,left+72,top+72);icon.draw(canvas);
                    // A real raster assertion catches an invisible/tinted-away glass layer.
                    assertNotEquals(surface,sheet.getPixel(left+36,top+48));
                    int back=sheet.getPixel(left+36,top+10);
                    assertTrue("The accent layer must not inherit the shadow's opacity",
                        Math.abs(android.graphics.Color.blue(back)-android.graphics.Color.blue(surface))>25
                        || Math.abs(android.graphics.Color.red(back)-android.graphics.Color.red(surface))>25);
                    icon.setBounds(left+23,top+82,left+47,top+106);icon.draw(canvas);
                }
            }
            java.io.File out=new java.io.File("D:/AxGram-analysis/glass-icons-2026-10-29/glass-icons-native.png");
            try(FileOutputStream stream=new FileOutputStream(out)) { assertTrue(sheet.compress(Bitmap.CompressFormat.PNG,100,stream)); }
        } finally {
            Theme.setColor(Theme.key_windowBackgroundWhite,oldSurface,false);
            Theme.setColor(Theme.key_windowBackgroundWhiteBlueText,oldAccent,false);
            AxAppearance.preferences().edit().clear().commit();
        }
    }
}
