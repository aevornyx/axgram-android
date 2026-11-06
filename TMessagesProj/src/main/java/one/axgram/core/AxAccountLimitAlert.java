package one.axgram.core;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

public final class AxAccountLimitAlert {
    private AxAccountLimitAlert() {
    }

    public static void show(BaseFragment fragment) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                .setTitle(LocaleController.getString(R.string.LimitReached))
                .setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(
                        R.string.LimitReachedAccountsPremium, UserConfig.getMaxAccountCount())))
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .create());
    }
}
