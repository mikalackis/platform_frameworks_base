/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.keyguard;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.UserHandle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.widget.LockPatternUtils;
import com.android.settingslib.animation.AppearAnimationUtils;
import com.android.settingslib.animation.DisappearAnimationUtils;

import ariel.providers.ArielSettings;

/**
 * Displays a PIN pad for unlocking when locked by ArielOS
 */
public class KeyguardArielLockView extends LinearLayout
        implements KeyguardSecurityView, EmergencyButton.EmergencyButtonCallback {

    private final AppearAnimationUtils mAppearAnimationUtils;
    private final DisappearAnimationUtils mDisappearAnimationUtils;
    private View mDivider;
    private int mDisappearYTranslation;
    private View[][] mViews;
    private TextView mAdminInfo;
    private Button btnCallAdmin;
    private NumPadKey mKeyClearAriel;

    public KeyguardArielLockView(Context context) {
        this(context, null);
    }

    public KeyguardArielLockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mAppearAnimationUtils = new AppearAnimationUtils(context);
        mDisappearAnimationUtils = new DisappearAnimationUtils(context,
                125, 0.6f /* translationScale */,
                0.45f /* delayScale */, AnimationUtils.loadInterpolator(
                mContext, android.R.interpolator.fast_out_linear_in));
        mDisappearYTranslation = getResources().getDimensionPixelSize(
                R.dimen.disappear_y_translation);
        Log.v("KeyguardSecurityModel", "I am inflated!");
    }

    protected void resetState() {
    }

    protected int getPasswordTextViewId() {
        return -1;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mAdminInfo = (TextView) findViewById(R.id.admin_info);
        mAdminInfo.setText("This phone has been locked by ArielOS administrator!");

        btnCallAdmin = (Button) findViewById(R.id.call_admin);
        btnCallAdmin.setText("Call my admin!");
        btnCallAdmin.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent callIntent = new Intent(Intent.ACTION_CALL);
                callIntent.setData(Uri.parse("tel:0652470801"));
                getContext().startActivityAsUser(callIntent,
                        ActivityOptions.makeCustomAnimation(getContext(), 0, 0).toBundle(),
                        new UserHandle(KeyguardUpdateMonitor.getCurrentUser()));
            }
        });

//        mKeyClearAriel = (NumPadKey) findViewById(R.id.key6);
//        mKeyClearAriel.setOnClickListener(new OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                ArielSettings.Secure.putInt(getContext().getContentResolver(),
//                        ArielSettings.Secure.ARIEL_SYSTEM_STATUS,
//                        ArielSettings.Secure.ARIEL_SYSTEM_STATUS_NORMAL);
//            }
//        });
    }

    @Override
    public void setKeyguardCallback(KeyguardSecurityCallback callback) {

    }

    @Override
    public void setLockPatternUtils(LockPatternUtils utils) {

    }

    @Override
    public void reset() {

    }

    @Override
    public void onPause() {

    }

    @Override
    public void onResume(int reason) {

    }

    @Override
    public boolean needsInput() {
        return false;
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return null;
    }

    @Override
    public void showPromptReason(int reason) {

    }

    @Override
    public void showMessage(String message, int color) {

    }

    @Override
    public void showUsabilityHint() {
    }

    public int getWrongPasswordStringId() {
        return R.string.kg_wrong_pin;
    }

    @Override
    public void startAppearAnimation() {
//        enableClipping(false);
//        setAlpha(1f);
//        setTranslationY(mAppearAnimationUtils.getStartTranslation());
//        AppearAnimationUtils.startTranslationYAnimation(this, 0 /* delay */, 500 /* duration */,
//                0, mAppearAnimationUtils.getInterpolator());
//        mAppearAnimationUtils.startAnimation2d(mViews,
//                new Runnable() {
//                    @Override
//                    public void run() {
//                        enableClipping(true);
//                    }
//                });
    }

    @Override
    public boolean startDisappearAnimation(final Runnable finishRunnable) {
//        enableClipping(false);
//        setTranslationY(0);
//        AppearAnimationUtils.startTranslationYAnimation(this, 0 /* delay */, 280 /* duration */,
//                mDisappearYTranslation, mDisappearAnimationUtils.getInterpolator());
//        DisappearAnimationUtils disappearAnimationUtils = mKeyguardUpdateMonitor
//                .needsSlowUnlockTransition()
//                        ? mDisappearAnimationUtilsLocked
//                        : mDisappearAnimationUtils;
//        disappearAnimationUtils.startAnimation2d(mViews,
//                new Runnable() {
//                    @Override
//                    public void run() {
//                        enableClipping(true);
//                        if (finishRunnable != null) {
//                            finishRunnable.run();
//                        }
//                    }
//                });
        return true;
    }

    private void enableClipping(boolean enable) {
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public void onEmergencyButtonClickedWhenInCall() {

    }
}
