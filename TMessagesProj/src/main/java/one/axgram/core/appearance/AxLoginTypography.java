package one.axgram.core.appearance;

import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

/** Apply the brand face to labels while retaining native password/code editing behavior. */
public final class AxLoginTypography {
    private static Typeface regular,bold;
    private AxLoginTypography(){}
    public static void apply(View view){
        if(regular==null){try{regular=Typeface.createFromAsset(view.getContext().getAssets(),"fonts/axgram_onest.ttf");bold=Typeface.create(regular,Typeface.BOLD);}catch(RuntimeException ignored){return;}}
        if(view instanceof TextView && !(view instanceof EditText)){
            TextView text=(TextView)view;Typeface old=text.getTypeface();
            if(old!=Typeface.MONOSPACE){Typeface target=old!=null&&old.isBold()?bold:regular;if(!target.equals(old))text.setTypeface(target);}
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)apply(group.getChildAt(i));}
    }
}
