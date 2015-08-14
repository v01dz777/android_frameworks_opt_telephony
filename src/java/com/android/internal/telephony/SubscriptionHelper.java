/*
 * Copyright (c) 2014-2015, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package com.android.internal.telephony;

import android.telephony.Rlog;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.UserHandle;
import android.os.SystemProperties;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionManager;
import android.util.Log;

import com.android.internal.telephony.ModemBindingPolicyHandler;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.uicc.IccRefreshResponse;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccCardApplication;


class SubscriptionHelper extends Handler {
    private static final String LOG_TAG = "SubHelper";
    private static SubscriptionHelper sInstance;

    private Context mContext;
    private CommandsInterface[] mCi;
    private int[] mSubStatus;
    private static int sNumPhones;
    // This flag is used to trigger Dds during boot-up
    // and when flex mapping performed
    private static boolean sTriggerDds = false;
    private class SetUiccTransaction {
        int mRequestCount;
        int mApp3gppResult;
        int mApp3gpp2Result;

        public SetUiccTransaction() {
            resetToDefault();
        }

        void incrementReqCount() {
            mRequestCount++;
        }

        void updateAppResult(int appType, int result) {
            mRequestCount--;
            if (appType == PhoneConstants.APPTYPE_USIM || appType == PhoneConstants.APPTYPE_SIM) {
                mApp3gppResult = result;
            } else if (appType == PhoneConstants.APPTYPE_CSIM ||
                    appType == PhoneConstants.APPTYPE_RUIM) {
                mApp3gpp2Result = result;
            }
        }

        boolean isResponseReceivedForAllApps() {
            return (mRequestCount == 0);
        }

        int getTransactionResult(int newSubState) {
            int result = PhoneConstants.SUCCESS;

            // In case of activation, if both APPS request failed treat
            // it as overall failure.
            // In case of deactivation, if any of the APP request failed
            // treat it as overall failure.
            if (newSubState == SubscriptionManager.ACTIVE &&
                    ((mApp3gppResult == SUB_SET_UICC_FAIL) &&
                    (mApp3gpp2Result == SUB_SET_UICC_FAIL))) {
                result = PhoneConstants.FAILURE;
            } else if (newSubState == SubscriptionManager.INACTIVE &&
                    ((mApp3gppResult == SUB_SET_UICC_FAIL) ||
                    (mApp3gpp2Result == SUB_SET_UICC_FAIL))) {
                result = PhoneConstants.FAILURE;
            }

            return result;
        }

        void resetToDefault() {
            mApp3gppResult = SUB_INIT_STATE;
            mApp3gpp2Result = SUB_INIT_STATE;
            mRequestCount = 0;
        }

        @Override
        public String toString() {
            return "reqCount " + mRequestCount + " 3gppApp result "
                    + mApp3gppResult + " 3gpp2 app result " + mApp3gpp2Result;
        }
    };
    private SetUiccTransaction[] mSetUiccTransaction;

    private static final String APM_SIM_NOT_PWDN_PROPERTY = "persist.radio.apm_sim_not_pwdn";
    private static final boolean sApmSIMNotPwdn = (SystemProperties.getInt(
            APM_SIM_NOT_PWDN_PROPERTY, 0) == 1);

    private static final int EVENT_SET_UICC_SUBSCRIPTION_DONE = 1;
    private static final int EVENT_REFRESH = 2;
    private static final int EVENT_SET_NW_MODE_DONE = 3;
    private static final int EVENT_GET_PREFERRED_NETWORK_TYPE = 4;

    public static final int SUB_SET_UICC_FAIL = -100;
    public static final int SUB_SIM_NOT_INSERTED = -99;
    public static final int SUB_INIT_STATE = -1;
    public static final int SUB_SET_UICC_SUCCESS = 1;
    private static boolean mNwModeUpdated = false;

    private final ContentObserver nwModeObserver =
        new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfUpdate) {
                logd("NwMode Observer onChange hit !!!");
                if (!mNwModeUpdated) return;
                //get nwMode from all slots in Db and update to subId table.
                updateNwModesInSubIdTable(true);
            }
        };


    public static SubscriptionHelper init(Context c, CommandsInterface[] ci) {
        synchronized (SubscriptionHelper.class) {
            if (sInstance == null) {
                sInstance = new SubscriptionHelper(c, ci);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    public static SubscriptionHelper getInstance() {
        if (sInstance == null) {
           Log.wtf(LOG_TAG, "getInstance null");
        }

        return sInstance;
    }

    private SubscriptionHelper(Context c, CommandsInterface[] ci) {
        mContext = c;
        mCi = ci;
        sNumPhones = TelephonyManager.getDefault().getPhoneCount();
        mSubStatus = new int[sNumPhones];
        mSetUiccTransaction = new SetUiccTransaction[sNumPhones];
        for (int i=0; i < sNumPhones; i++ ) {
            mSubStatus[i] = SUB_INIT_STATE;
            Integer index = new Integer(i);
            // Register for SIM Refresh events
            mCi[i].registerForIccRefresh(this, EVENT_REFRESH, index);
            mSetUiccTransaction[i] = new SetUiccTransaction();
        }
        mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.PREFERRED_NETWORK_MODE), false, nwModeObserver);


        logd("SubscriptionHelper init by Context, num phones = "
                + sNumPhones + " ApmSIMNotPwdn = " + sApmSIMNotPwdn);
    }

    private void updateNwModesInSubIdTable(boolean override) {
        SubscriptionController subCtrlr = SubscriptionController.getInstance();
        for (int i=0; i < sNumPhones; i++ ) {
            int[] subIdList = subCtrlr.getSubId(i);
            if (subIdList != null && subIdList[0] > 0) {
                int nwModeInDb;
                try {
                    nwModeInDb = TelephonyManager.getIntAtIndex(mContext.getContentResolver(),
                            Settings.Global.PREFERRED_NETWORK_MODE, i);
                } catch (SettingNotFoundException snfe) {
                    loge("Settings Exception Reading Value At Index[" + i +
                            "] Settings.Global.PREFERRED_NETWORK_MODE");
                    nwModeInDb = RILConstants.PREFERRED_NETWORK_MODE;
                }
                int nwModeinSubIdTable = subCtrlr.getNwMode(subIdList[0]);
                logd("updateNwModesInSubIdTable: nwModeinSubIdTable: " + nwModeinSubIdTable
                        + ", nwModeInDb: " + nwModeInDb);

                //store Db value to table only if value in table is default
                //OR if override is set to True.
                if (override || nwModeinSubIdTable == SubscriptionManager.DEFAULT_NW_MODE) {
                    subCtrlr.setNwMode(subIdList[0], nwModeInDb);
                }
            }
        }
    }

    @Override
    public void handleMessage(Message msg) {
        switch(msg.what) {
            case EVENT_SET_UICC_SUBSCRIPTION_DONE:
                logd("EVENT_SET_UICC_SUBSCRIPTION_DONE");
                processSetUiccSubscriptionDone(msg);
                break;
            case EVENT_REFRESH:
                logd("EVENT_REFRESH");
                processSimRefresh((AsyncResult)msg.obj);
                break;
            case EVENT_SET_NW_MODE_DONE:
                handleSetPrefNwModeDone(msg);
                break;
            case EVENT_GET_PREFERRED_NETWORK_TYPE:
                handleGetPreferredNetworkTypeResponse(msg);
                break;
           default:
           break;
        }
    }

    private void handleGetPreferredNetworkTypeResponse(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;
        Integer phoneId = (Integer)ar.userObj;
        if (ar.exception == null) {
            int modemNetworkMode = ((int[])ar.result)[0];

            //check that modemNetworkMode is from an accepted value
            if (isNwModeValid(modemNetworkMode)) {
                //update nwMode value to the DB
                logd("Updating nw mode in DB for slot[" + phoneId+ "] with " + modemNetworkMode);
                TelephonyManager.putIntAtIndex(mContext.getContentResolver(),
                        android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                        phoneId, modemNetworkMode);
            } else {
                loge("handleGetPreferredNetworkTypeResponse: InValid for slot : " + phoneId);
            }
        } else {
            loge("handleGetPreferredNetworkTypeResponse: Failed for slot : "+ phoneId);
        }
    }

    private boolean isNwModeValid(int nwMode) {
        return (nwMode >= Phone.NT_MODE_WCDMA_PREF) &&
                    (nwMode <= Phone.NT_MODE_TD_SCDMA_LTE_CDMA_EVDO_GSM_WCDMA);
    }

    private void handleSetPrefNwModeDone(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;
        if (ar.exception != null) {
            logd("Failed to set preferred network mode as per simInfo Table");

            for (int i=0; i < sNumPhones; i++ ) {
                logd("Get nw mode from modem and set to DB for slot :" + i);
                Message getNwModeMsg = Message.obtain(this, EVENT_GET_PREFERRED_NETWORK_TYPE,
                    new Integer(i));
                mCi[i].getPreferredNetworkType(getNwModeMsg);
            }
        } else {
            logd("Success to set pref nw mode as per sim info table on a slot");
        }

    }

    public boolean needSubActivationAfterRefresh(int slotId) {
        return (sNumPhones > 1  && mSubStatus[slotId] == SUB_INIT_STATE);
    }

    public void updateSubActivation(int[] simStatus, boolean isStackReadyEvent) {
        boolean isPrimarySubFeatureEnable =
               SystemProperties.getBoolean("persist.radio.primarycard", false);
        SubscriptionController subCtrlr = SubscriptionController.getInstance();
        boolean setUiccSent = false;
        // When isPrimarySubFeatureEnable is enabled apps will take care
        // of sending DDS on MMode SUB so no need of triggering DDS from here.
        if (isStackReadyEvent && !isPrimarySubFeatureEnable) {
            sTriggerDds = true;
        }

        for (int slotId = 0; slotId < sNumPhones; slotId++) {
            if (simStatus[slotId] == SUB_SIM_NOT_INSERTED) {
                mSubStatus[slotId] = simStatus[slotId];
                logd(" Sim not inserted in slot [" + slotId + "] simStatus= " + simStatus[slotId]);
                continue;
            }
            int[] subId = subCtrlr.getSubId(slotId);
            int subState = subCtrlr.getSubState(subId[0]);

            logd("setUicc for [" + slotId + "] = " + subState + "subId = " + subId[0] +
                    " prev subState = " + mSubStatus[slotId] + " stackReady " + isStackReadyEvent);
            // Do not send SET_UICC if its already sent with same state
            if ((mSubStatus[slotId] != subState) || isStackReadyEvent) {
                // If sim card present in the slot, get the stored sub status and
                // perform the activation/deactivation of subscription
                setUiccSubscription(slotId, subState);
                setUiccSent = true;
            }
        }
        // If at least one uiccrequest sent, updateUserPrefs() will be called
        // from processSetUiccSubscriptionDone()
        if (isAllSubsAvailable() && (!setUiccSent)) {
            logd("Received all sim info, update user pref subs, triggerDds= " + sTriggerDds);
            subCtrlr.updateUserPrefs(sTriggerDds);
            sTriggerDds = false;
        }
    }

    public void updateNwMode() {
        SubscriptionController subCtrlr = SubscriptionController.getInstance();
        int[] prefNwModeInDB = new int[sNumPhones];
        int[] nwModeinSubIdTable = new int[sNumPhones];
        boolean updateRequired = false;

        updateNwModesInSubIdTable(false);
        mNwModeUpdated = true;

        for (int i=0; i < sNumPhones; i++ ) {
            int[] subIdList = subCtrlr.getSubId(i);
            try {
                prefNwModeInDB[i] = TelephonyManager.getIntAtIndex(mContext.getContentResolver(),
                        android.provider.Settings.Global.PREFERRED_NETWORK_MODE, i);
            } catch (SettingNotFoundException snfe) {
                loge("updateNwMode: Could not find PREFERRED_NETWORK_MODE!!!");
                prefNwModeInDB[i] = Phone.PREFERRED_NT_MODE;
            }

            if (subIdList != null && subIdList[0] > 0) {
                int subId = subIdList[0];
                if (!SubscriptionManager.isValidSubscriptionId(subId)) {
                    nwModeinSubIdTable[i] = SubscriptionManager.DEFAULT_NW_MODE;
                } else {
                    nwModeinSubIdTable[i] = subCtrlr.getNwMode(subId);
                }
                if (nwModeinSubIdTable[i] == SubscriptionManager.DEFAULT_NW_MODE){
                    updateRequired = false;
                    break;
                }
                if (nwModeinSubIdTable[i] != prefNwModeInDB[i]
                        && isNwModeValid(nwModeinSubIdTable[i])) {
                    updateRequired = true;
                }
            }
        }
        logd("updateNwMode: updateRequired in Modem: " + updateRequired);

        if (updateRequired) {
            for (int i=0; i < sNumPhones; i++ ) {
                logd("Updating Value in DB for slot[" + i + "] with " + nwModeinSubIdTable[i]);
                TelephonyManager.putIntAtIndex( mContext.getContentResolver(),
                        android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                        i, nwModeinSubIdTable[i]);
            }
            Message msg = obtainMessage(EVENT_SET_NW_MODE_DONE);
            ModemBindingPolicyHandler.getInstance().updatePrefNwTypeIfRequired(msg);
        }
    }

    public void setUiccSubscription(int slotId, int subStatus) {
        boolean set3GPPDone = false, set3GPP2Done = false;
        UiccCard uiccCard = UiccController.getInstance().getUiccCard(slotId);
        if (uiccCard == null) {
            logd("setUiccSubscription: slotId:" + slotId + " card info not available");
            return;
        }

        //Activate/Deactivate first 3GPP and 3GPP2 app in the sim, if available
        for (int i = 0; i < uiccCard.getNumApplications(); i++) {
            int appType = uiccCard.getApplicationIndex(i).getType().ordinal();
            if (set3GPPDone == false && (appType == PhoneConstants.APPTYPE_USIM ||
                    appType == PhoneConstants.APPTYPE_SIM)) {
                mSetUiccTransaction[slotId].incrementReqCount();
                Message msgSetUiccSubDone = Message.obtain(
                        this, EVENT_SET_UICC_SUBSCRIPTION_DONE,
                        slotId, subStatus, new Integer(appType));
                mCi[slotId].setUiccSubscription(slotId, i, slotId, subStatus, msgSetUiccSubDone);
                set3GPPDone = true;
            } else if (set3GPP2Done == false && (appType == PhoneConstants.APPTYPE_CSIM ||
                    appType == PhoneConstants.APPTYPE_RUIM)) {
                mSetUiccTransaction[slotId].incrementReqCount();
                Message msgSetUiccSubDone = Message.obtain(
                        this, EVENT_SET_UICC_SUBSCRIPTION_DONE,
                        slotId, subStatus, new Integer(appType));
                mCi[slotId].setUiccSubscription(slotId, i, slotId, subStatus, msgSetUiccSubDone);
                set3GPP2Done = true;
            }

            if (set3GPPDone && set3GPP2Done) break;
        }
    }

    /**
     * Handles the EVENT_SET_UICC_SUBSCRPTION_DONE.
     * @param ar
     */
    private void processSetUiccSubscriptionDone(Message msg) {
        SubscriptionController subCtrlr = SubscriptionController.getInstance();
        AsyncResult ar = (AsyncResult)msg.obj;
        int slotId = msg.arg1;
        int newSubState = msg.arg2;
        int[] subId = subCtrlr.getSubIdUsingSlotId(slotId);

        mSetUiccTransaction[slotId].updateAppResult((Integer)ar.userObj,
                (ar.exception != null) ? SUB_SET_UICC_FAIL : SUB_SET_UICC_SUCCESS);
        if (!mSetUiccTransaction[slotId].isResponseReceivedForAllApps()) {
            logi("Waiting for more responses " + mSetUiccTransaction[slotId] + " slotId " + slotId);
            return;
        }
        logd(" SubParams info " + mSetUiccTransaction[slotId] + " slotId " + slotId);

        if (mSetUiccTransaction[slotId].
                getTransactionResult(newSubState) == PhoneConstants.FAILURE) {
            loge("Exception in SET_UICC_SUBSCRIPTION, slotId = " + slotId
                    + " newSubState " + newSubState);
            // broadcast set uicc failure
            mSubStatus[slotId] = SUB_SET_UICC_FAIL;
            broadcastSetUiccResult(slotId, newSubState, PhoneConstants.FAILURE);
            mSetUiccTransaction[slotId].resetToDefault();
            return;
        }
        mSetUiccTransaction[slotId].resetToDefault();

        int subStatus = subCtrlr.getSubState(subId[0]);
        if (newSubState != subStatus) {
            subCtrlr.setSubState(subId[0], newSubState);
        }
        broadcastSetUiccResult(slotId, newSubState, PhoneConstants.SUCCESS);

        mSubStatus[slotId] = newSubState;
        // After activating all subs, updated the user preferred sub values
        if (isAllSubsAvailable()) {
            logd("Received all subs, now update user preferred subs, slotid = " + slotId
                    + " newSubState = " + newSubState + " sTriggerDds = " + sTriggerDds);
            subCtrlr.updateUserPrefs(sTriggerDds);
            sTriggerDds = false;
        }
    }

    private void processSimRefresh (AsyncResult ar) {
        if (ar.exception == null && ar.result != null) {
            Integer index = new Integer(PhoneConstants.DEFAULT_CARD_INDEX);
            index = (Integer)ar.userObj;
            IccRefreshResponse state = (IccRefreshResponse)ar.result;
            logi(" Received SIM refresh, reset sub state " +
                    index + " old sub state " + mSubStatus[index] +
                    " refreshResult = " + state.refreshResult);
            if (state.refreshResult == IccRefreshResponse.REFRESH_RESULT_RESET) {
                //Subscription activation needed.
                mSubStatus[index] = SUB_INIT_STATE;
            }
        } else {
            loge("processSimRefresh received without input");
        }
    }

    private void broadcastSetUiccResult(int slotId, int newSubState, int result) {
        int[] subId = SubscriptionController.getInstance().getSubIdUsingSlotId(slotId);
        Intent intent = new Intent(TelephonyIntents.ACTION_SUBSCRIPTION_SET_UICC_RESULT);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        SubscriptionManager.putPhoneIdAndSubIdExtra(intent, slotId, subId[0]);
        intent.putExtra(TelephonyIntents.EXTRA_RESULT, result);
        intent.putExtra(TelephonyIntents.EXTRA_NEW_SUB_STATE, newSubState);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private boolean isAllSubsAvailable() {
        boolean allSubsAvailable = true;

        for (int i=0; i < sNumPhones; i++) {
            if (mSubStatus[i] == SUB_INIT_STATE) {
                allSubsAvailable = false;
            }
        }
        return allSubsAvailable;
    }

    public boolean isRadioOn(int phoneId) {
        return mCi[phoneId].getRadioState().isOn();
    }

    public boolean isRadioAvailable(int phoneId) {
        return mCi[phoneId].getRadioState().isAvailable();
    }

    public boolean isApmSIMNotPwdn() {
        return sApmSIMNotPwdn;
    }

    public boolean proceedToHandleIccEvent(int slotId) {
        int apmState = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0);

        // If SIM powers down in APM, telephony needs to send SET_UICC
        // once radio turns ON also do not handle process SIM change events
        if ((!sApmSIMNotPwdn) && (!isRadioOn(slotId) || (apmState == 1))) {
            logi(" proceedToHandleIccEvent, radio off/unavailable, slotId = " + slotId);
            mSubStatus[slotId] = SUB_INIT_STATE;
        }

        // Do not handle if SIM powers down in APM mode
        if ((apmState == 1) && (!sApmSIMNotPwdn)) {
            logd(" proceedToHandleIccEvent, sApmSIMNotPwdn = " + sApmSIMNotPwdn);
            return false;
        }


        // Seems SSR happenned or RILD crashed, do not handle SIM change events
        if (!isRadioAvailable(slotId)) {
            logi(" proceedToHandleIccEvent, radio not available, slotId = " + slotId);
            mSubStatus[slotId] = SUB_INIT_STATE;
            return false;
        }
        return true;
    }

    private static void logd(String message) {
        Rlog.d(LOG_TAG,  message);
    }

    private void logi(String msg) {
        Rlog.i(LOG_TAG,  msg);
    }

    private void loge(String msg) {
        Rlog.e(LOG_TAG,  msg);
    }
}
