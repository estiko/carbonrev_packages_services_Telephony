/*
 * Copyright (c) 2011-2013 The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.phone;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.provider.Settings;
import android.telephony.MSimTelephonyManager;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.codeaurora.telephony.msim.SubscriptionManager;

/**
 * Top level "Call settings" UI for MSIM; see res/xml/msim_call_feature_setting.xml
 *
 * This preference screen is the root of the "MSim Call settings" hierarchy
 * available from the Phone app; the settings here let you control various
 * features related to phone calls (including SIP
 * settings, the "Respond via SMS" feature, and others.)  It's used only
 * on voice-capable phone devices.
 *
 * Note that this activity is part of the package com.android.phone, even
 * though you reach it from the "Phone" app (i.e. DialtactsActivity) which
 * is from the package com.android.contacts.
 *
 * For the "MSim Mobile network settings" screen under the main Settings app,
 * See {@link MSimMobileNetworkSettings}.
 *
 * @see com.android.phone.MSimMobileNetworkSettings
 */
public class MSimCallFeaturesSetting extends CallFeaturesSetting {
    private static final String LOG_TAG = "MSimCallFeaturesSetting";
    private static final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    // String keys for preference lookup
    // TODO: Naming these "BUTTON_*" is confusing since they're not actually buttons(!)
    private static final String BUTTON_SELECT_SUB_KEY  = "button_call_independent_serv";
    private static final String BUTTON_XDIVERT_KEY     = "button_xdivert";

    private static final String BUTTON_ALLOW_CALL_RECORDING =
            "button_allow_call_recording";

    // To track whether a confirmation dialog was clicked.
    private boolean mDialogClicked;
    private Dialog mWaiverDialog;

    private CheckBoxPreference mAllowCallRecording;
    private PreferenceScreen mButtonXDivert;
    private int mNumPhones;
    private SubscriptionManager mSubManager;

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference == mButtonXDivert) {
            processXDivert();
            return true;
        } else if (preference == mAllowCallRecording) {
            if (mAllowCallRecording.isChecked()) {
                // User is trying to enable the feature, display the waiver
                mDialogClicked = false;
                dismissDialog();
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setMessage(R.string.call_recording_waiver_body);
                builder.setTitle(R.string.call_recording_waiver_title);
                builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (dialog == mWaiverDialog) {
                            if (!mDialogClicked) {
                                mAllowCallRecording.setChecked(false);
                            }
                            mWaiverDialog = null;
                        }
                    }
                });

                builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (dialog == mWaiverDialog) {
                            if (which == DialogInterface.BUTTON_POSITIVE) {
                                mDialogClicked = true;
                                Settings.System.putBoolean(getContentResolver(),
                                        Settings.System.ALLOW_CALL_RECORDING, true);
                            }
                        }
                    }
                });

                mWaiverDialog = builder.show();
                mWaiverDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {

                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        // Assuming that onClick gets called first
                        if (dialog == mWaiverDialog) {
                            if (!mDialogClicked) {
                                mAllowCallRecording.setChecked(false);
                            }
                            mWaiverDialog = null;
                        }
                    }
                });
            } else {
                Settings.System.putBoolean(getContentResolver(),
                        Settings.System.ALLOW_CALL_RECORDING, false);
            }
        }
        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        log("onCreate(). Intent: " + getIntent());

        mSubManager = SubscriptionManager.getInstance();

        mButtonXDivert = (PreferenceScreen) findPreference(BUTTON_XDIVERT_KEY);

        PreferenceScreen selectSub = (PreferenceScreen) findPreference(BUTTON_SELECT_SUB_KEY);
        if (selectSub != null) {
            Intent intent = selectSub.getIntent();
            intent.putExtra(SelectSubscription.PACKAGE, "com.android.phone");
            intent.putExtra(SelectSubscription.TARGET_CLASS,
                    "com.android.phone.MSimCallFeaturesSubSetting");
        }

        mNumPhones = MSimTelephonyManager.getDefault().getPhoneCount();
        if (mButtonXDivert != null) {
            mButtonXDivert.setOnPreferenceChangeListener(this);
        }

        mAllowCallRecording = (CheckBoxPreference) findPreference(BUTTON_ALLOW_CALL_RECORDING);
        if (mAllowCallRecording != null) {
            mAllowCallRecording.setChecked(Settings.System.getBoolean(getContentResolver(),
                    Settings.System.ALLOW_CALL_RECORDING, false));
        }
    }

    @Override
    protected void onCreateVoicemailPrefs(Bundle savedInstanceState) {
       //Do Nothing
    }

    @Override
    protected void onResumeVoicemailPrefs() {
        //Do Nothing
    }

    @Override
    protected void onCreateRingtonePrefs(PreferenceScreen preferenceScreen) {
        //Do Nothing
    }

    @Override
    protected void onResumeRingtonePrefs() {
        //Do Nothing
    }

    private boolean isAllSubActive() {
        for (int i = 0; i < mNumPhones; i++) {
            if (!mSubManager.isSubActive(i)) return false;
        }
        return true;
    }

    private boolean isAnySubCdma() {
        for (int i = 0; i < mNumPhones; i++) {
            Phone phone = MSimPhoneGlobals.getInstance().getPhone(i);
            if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) return true;
        }
        return false;
    }

    private boolean isValidLine1Number(String[] line1Numbers) {
        for (int i = 0; i < mNumPhones; i++) {
            if (TextUtils.isEmpty(line1Numbers[i])) return false;
        }
        return true;
    }

    private void processXDivert() {
        String[] line1Numbers = new String[mNumPhones];
        for (int i = 0; i < mNumPhones; i++) {
            Phone phone = MSimPhoneGlobals.getInstance().getPhone(i);
            String msisdn = phone.getMsisdn();  // may be null or empty
            if (!TextUtils.isEmpty(msisdn)) {
                //Populate the line1Numbers only if it is not null
               line1Numbers[i] = PhoneNumberUtils.formatNumber(msisdn);
            }

            log("SUB:" + i + " phonetype = " + phone.getPhoneType()
                    + " isSubActive = " + mSubManager.isSubActive(i)
                    + " line1Number = " + line1Numbers[i]);
        }
        if (!isAllSubActive()) {
            //Is a subscription is deactived/or only one SIM is present,
            //dialog would be displayed stating the same.
            displayAlertDialog(R.string.xdivert_sub_absent);
        } else if (isAnySubCdma()) {
            //X-Divert is not supported for CDMA phone.Hence for C+G / C+C,
            //dialog would be displayed stating the same.
            displayAlertDialog(R.string.xdivert_not_supported);
        } else if (!isValidLine1Number(line1Numbers)) {
            //SIM records does not have msisdn, hence ask user to enter
            //the phone numbers.
            Intent intent = new Intent();
            intent.setClass(this, XDivertPhoneNumbers.class);
            startActivity(intent);
        } else {
            //SIM records have msisdn.Hence directly process
            //XDivert feature
            processXDivertCheckBox(line1Numbers);
        }
    }

    private void displayAlertDialog(int resId) {
        new AlertDialog.Builder(this).setMessage(resId)
            .setTitle(R.string.xdivert_title)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        Log.d(LOG_TAG, "X-Divert onClick");
                    }
                })
            .show()
            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    public void onDismiss(DialogInterface dialog) {
                        Log.d(LOG_TAG, "X-Divert onDismiss");
                    }
            });
    }

    private void processXDivertCheckBox(String[] line1Numbers) {
        log("processXDivertCheckBox line1Numbers = "
                + java.util.Arrays.toString(line1Numbers));
        Intent intent = new Intent();
        intent.setClass(this, XDivertSetting.class);
        intent.putExtra(XDivertUtility.LINE1_NUMBERS, line1Numbers);
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mButtonXDivert != null) {
            if (!isAllSubActive()) mButtonXDivert.setEnabled(false);
        }
    }

    @Override
    protected void createImsSettings() {}

    @Override
    protected void createSipCallSettings() {}

    @Override
    protected void addOptionalPrefs(PreferenceScreen preferenceScreen) {}

    @Override
    protected void removeOptionalPrefs(PreferenceScreen preferenceScreen) {
        super.removeOptionalPrefs(preferenceScreen);
        if (!getResources().getBoolean(R.bool.config_show_xdivert)) {
            Preference xdivert = findPreference(BUTTON_XDIVERT_KEY);
            preferenceScreen.removePreference(xdivert);
        }
    }

    protected int getPreferencesResource() {
        return R.xml.msim_call_feature_setting;
    }

    private static void log(String msg) {
        if (DBG) Log.d(LOG_TAG, msg);
    }

    private void dismissDialog() {
        if (mWaiverDialog != null) {
            mWaiverDialog.dismiss();
            mWaiverDialog = null;
        }
    }
}
