/* AxGram welcome. Authentication and registration remain in Telegram's LoginActivity. */
package org.telegram.ui;

import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import java.util.ArrayList;
import one.axgram.core.appearance.AxAppearance;
import one.axgram.core.appearance.AxWelcomeView;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.SimpleThemeDescription;

public class IntroActivity extends BaseFragment {
    private AxWelcomeView welcome;
    private boolean themeStep,played,startPressed,onLogout;
    /** Return from the initial phone page to the preceding appearance choice. */
    public static IntroActivity forAppearanceSelection(){
        IntroActivity intro=new IntroActivity();intro.themeStep=true;intro.played=true;return intro;
    }
    @Override public View createView(Context context) {
        actionBar.setAddToContainer(false);
        welcome=new AxWelcomeView(context,new AxWelcomeView.Listener(){
            @Override public void onThemeSelected(int mode){
                themeStep=welcome.isThemeStep();AxAppearance.applyMode(mode);
                welcome.updateAppearance(mode,AxAppearance.isModeDark(mode));
            }
            @Override public void onContinue(){
                if(startPressed)return;startPressed=true;welcome.stopEntrance();
                if(!presentFragment(new LoginActivity(),true))startPressed=false;
            }
            @Override public void onLanguageRequested(){themeStep=welcome.isThemeStep();presentFragment(new LanguageSelectActivity());}
        },id->id==R.string.Language?LocaleController.getCurrentLanguageName():LocaleController.getString(id),AxAppearance.mode(),AxAppearance.isModeDark(AxAppearance.mode()),themeStep);
        played=themeStep;fragmentView=welcome;return fragmentView;
    }
    @Override public void onResume(){
        super.onResume();if(welcome==null)return;
        themeStep=welcome.isThemeStep();welcome.updateAppearance(AxAppearance.mode(),AxAppearance.isModeDark(AxAppearance.mode()));
    }
    @Override public void onBecomeFullyVisible(){
        super.onBecomeFullyVisible();
        final AxWelcomeView view=welcome;
        if(view==null||played||view.isThemeStep())return;
        view.postOnAnimation(()->{
            if(welcome==view&&!isPaused()&&!played&&!view.isThemeStep()&&view.isAttachedToWindow()){
                played=true;view.playEntrance();
            }
        });
    }
    @Override public void onPause(){if(welcome!=null)welcome.stopEntrance();super.onPause();}
    @Override public void onFragmentDestroy(){if(welcome!=null)welcome.stopEntrance();welcome=null;super.onFragmentDestroy();}
    @Override public boolean onBackPressed(boolean invoked){
        if(welcome!=null&&welcome.isThemeStep()){if(invoked){welcome.showThemeStep(false);themeStep=false;}return false;}
        return super.onBackPressed(invoked);
    }
    @Override public void saveSelfArgs(Bundle args){super.saveSelfArgs(args);args.putBoolean("ax_welcome_theme_step",welcome!=null?welcome.isThemeStep():themeStep);args.putBoolean("ax_welcome_played",played);}
    @Override public void restoreSelfArgs(Bundle args){super.restoreSelfArgs(args);themeStep=args.getBoolean("ax_welcome_theme_step");played=args.getBoolean("ax_welcome_played");}
    public IntroActivity setOnLogout(){onLogout=true;return this;}
    @Override public AnimatorSet onCustomTransitionAnimation(boolean isOpen,Runnable callback){
        if(!onLogout)return null;AnimatorSet set=new AnimatorSet();set.playTogether(ValueAnimator.ofFloat(0,1));set.setDuration(50);return set;
    }
    @Override public boolean hasForceLightStatusBar(){return true;}
    @Override public boolean isLightStatusBar(){return !Theme.isCurrentThemeDark();}
    @Override public ArrayList<ThemeDescription> getThemeDescriptions(){
        return SimpleThemeDescription.createThemeDescriptions(()->{if(welcome!=null){themeStep=welcome.isThemeStep();welcome.updateAppearance(AxAppearance.mode(),AxAppearance.isModeDark(AxAppearance.mode()));}},Theme.key_windowBackgroundWhite,Theme.key_windowBackgroundWhiteBlackText,Theme.key_windowBackgroundWhiteBlueText);
    }
}

/*
 * This introductory class was originally part of Telegram for Android v. 5.x.x.
 * Copyright Nikolai Kudashov, 2013-2018.
 * Licensed under GNU GPL version 2 or later; see LICENSE.
 * AxGram: native welcome and appearance UI modified 2026-10-30.
 */
