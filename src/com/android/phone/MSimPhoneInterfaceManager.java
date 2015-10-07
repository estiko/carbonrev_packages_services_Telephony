/*
 * Copyright (c) 2012-2013 The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2006 The Android Open Source Project
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

import android.app.AppOpsManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.NeighboringCellInfo;
import android.telephony.CellInfo;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.telephony.MSimTelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.DefaultPhoneNotifier;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.msim.ITelephonyMSim;
import com.android.internal.telephony.uicc.IccIoResult;
import com.android.internal.telephony.uicc.IccUtils;
import com.codeaurora.telephony.msim.SubscriptionManager;

import java.util.ArrayList;
import java.util.List;

import static com.android.internal.telephony.MSimConstants.SUBSCRIPTION_KEY;

/**
 * Implementation of the ITelephonyMSim interface for Multi SIM.
 */
public class MSimPhoneInterfaceManager extends ITelephonyMSim.Stub {
    private static final String LOG_TAG = "MSimPhoneInterfaceManager";
    private static final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    // Message codes used with mMainThreadHandler
    private static final int CMD_HANDLE_PIN_MMI = 1;
    private static final int CMD_HANDLE_NEIGHBORING_CELL = 2;
    private static final int EVENT_NEIGHBORING_CELL_DONE = 3;
    private static final int CMD_ANSWER_RINGING_CALL = 4;
    private static final int CMD_END_CALL = 5;  // not used yet
    private static final int CMD_SILENCE_RINGER = 6;
    private static final int CMD_SET_DATA_SUBSCRIPTION = 14;
    private static final int EVENT_SET_DATA_SUBSCRIPTION_DONE = 15;
    private static final int CMD_EXCHANGE_APDU = 16;
    private static final int EVENT_EXCHANGE_APDU_DONE = 17;
    private static final int CMD_OPEN_CHANNEL = 18;
    private static final int EVENT_OPEN_CHANNEL_DONE = 19;
    private static final int CMD_CLOSE_CHANNEL = 20;
    private static final int EVENT_CLOSE_CHANNEL_DONE = 21;
    private static final int CMD_SIM_IO = 22;
    private static final int EVENT_SIM_IO_DONE = 23;
    private static final int CMD_SIM_GET_ATR = 24;
    private static final int EVENT_SIM_GET_ATR_DONE = 25;

    /** The singleton instance. */
    private static MSimPhoneInterfaceManager sInstance;

    PhoneGlobals mApp;
    Phone mPhone;
    CallManager mCM;
    MainThreadHandler mMainThreadHandler;
    CallHandlerServiceProxy mCallHandlerService;

    private int mPhoneCount = MSimTelephonyManager.getDefault().getPhoneCount();
    private int[] mLastError = new int[mPhoneCount];
    // Error codes for SmartCardService apis
    private static final int SUCCESS = 0;
    private static final int GENERIC_FAILURE = 1;
    private static final int ERROR_MISSING_RESOURCE = 2;
    private static final int ERROR_NO_SUCH_ELEMENT = 3;
    private static final int RADIO_NOT_AVAILABLE = 4;
    private static final int ERROR_INVALID_PARAMETER = 5;

    private static final int LEN_ONE_BYTE = 1 ;
    private static final int LEN_TWO_BYTE = 2 ;

    // The error returned for SmartCardService apis.
    private static final int RESULT_OPEN_CHANNEL_FAILURE = 0;
    private static final int RESULT_CLOSE_CHANNEL_FAILURE = -1;

    private static final class IccApduArgument {
        public int channel, cla, command, p1, p2, p3;
        public String data;

        public IccApduArgument(int cla, int command, int channel,
                int p1, int p2, int p3, String data) {
            this.channel = channel;
            this.cla = cla;
            this.command = command;
            this.p1 = p1;
            this.p2 = p2;
            this.p3 = p3;
            this.data = data;
        }
    }

    /**
     * A request object for use with {@link MainThreadHandler}. Requesters should wait() on the
     * request after sending. The main thread will notify the request when it is complete.
     */
    private static final class MainThreadRequest {
        /** The argument to use for the request */
        public Object argument;
        /** The argument to use for the request */
        public Object argument2;
        /** The result of the request that is run on the main thread */
        public Object result;

        public MainThreadRequest(Object argument, Object argument2) {
            this.argument = argument;
            this.argument2 = argument2;
        }
    }

    /**
     * A handler that processes messages on the main thread in the phone process. Since many
     * of the Phone calls are not thread safe this is needed to shuttle the requests from the
     * inbound binder threads to the main thread in the phone process.  The Binder thread
     * may provide a {@link MainThreadRequest} object in the msg.obj field that they are waiting
     * on, which will be notified when the operation completes and will contain the result of the
     * request.
     *
     * <p>If a MainThreadRequest object is provided in the msg.obj field,
     * note that request.result must be set to something non-null for the calling thread to
     * unblock.
     */
    private final class MainThreadHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            MainThreadRequest request;
            Message onCompleted;
            AsyncResult ar;
            int sub = getDefaultSubscription();

            switch (msg.what) {
                case CMD_HANDLE_PIN_MMI:
                    request = (MainThreadRequest) msg.obj;
                    sub = (Integer) request.argument2;
                    Phone phone = PhoneGlobals.getInstance().getPhone(sub);
                    Log.i(LOG_TAG,"CMD_HANDLE_PIN_MMI: sub :" + phone.getSubscription());
                    request.result = Boolean.valueOf(
                            phone.handlePinMmi((String) request.argument));
                    // Wake up the requesting thread
                    synchronized (request) {
                        request.notifyAll();
                    }
                    break;

                case CMD_HANDLE_NEIGHBORING_CELL:
                    request = (MainThreadRequest) msg.obj;
                    onCompleted = obtainMessage(EVENT_NEIGHBORING_CELL_DONE,
                            request);
                    mPhone.getNeighboringCids(onCompleted);
                    break;

                case EVENT_NEIGHBORING_CELL_DONE:
                    ar = (AsyncResult) msg.obj;
                    request = (MainThreadRequest) ar.userObj;
                    if (ar.exception == null && ar.result != null) {
                        request.result = ar.result;
                    } else {
                        // create an empty list to notify the waiting thread
                        request.result = new ArrayList<NeighboringCellInfo>();
                    }
                    // Wake up the requesting thread
                    synchronized (request) {
                        request.notifyAll();
                    }
                    break;

                case CMD_ANSWER_RINGING_CALL:
                    answerRingingCallInternal();
                    break;

                case CMD_SILENCE_RINGER:
                    silenceRingerInternal();
                    break;

                case CMD_END_CALL:
                    request = (MainThreadRequest) msg.obj;
                    boolean hungUp = false;
                    sub = (Integer) request.argument;
                    log("Ending call on subscription =" + sub);
                    phone = mApp.getPhone(sub);
                    int phoneType = phone.getPhoneType();
                    if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                        // CDMA: If the user presses the Power button we treat it as
                        // ending the complete call session
                        hungUp = PhoneUtils.hangupRingingAndActive(phone);
                    } else if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                        // GSM: End the call as per the Phone state
                        hungUp = PhoneUtils.hangup(mCM);
                    } else {
                        throw new IllegalStateException("Unexpected phone type: " + phoneType);
                    }
                    if (DBG) log("CMD_END_CALL: " + (hungUp ? "hung up!" : "no call to hang up"));
                    request.result = hungUp;
                    // Wake up the requesting thread
                    synchronized (request) {
                        request.notifyAll();
                    }
                    break;

                case CMD_SET_DATA_SUBSCRIPTION:
                    request = (MainThreadRequest) msg.obj;
                    int subscription = (Integer) request.argument;
                    boolean isTempSwitch = (Boolean) request.argument2;
                    onCompleted = obtainMessage(EVENT_SET_DATA_SUBSCRIPTION_DONE, request);
                    SubscriptionManager subManager = SubscriptionManager.getInstance();
                    if (subManager != null) {
                        subManager.setDataSubscription(subscription, isTempSwitch, onCompleted);
                    } else {
                        // need to return false;
                        // Wake up the requesting thread
                        request.result = false;
                        synchronized (request) {
                            request.notifyAll();
                        }
                    }
                    break;

                case EVENT_SET_DATA_SUBSCRIPTION_DONE:
                    boolean retStatus = false;
                    ar = (AsyncResult) msg.obj;
                    request = (MainThreadRequest)ar.userObj;

                    if (ar.exception == null && ar.result != null) {
                        boolean result = (Boolean)ar.result;
                        if (result) {
                            retStatus = true;
                        }
                    }
                    request.result = retStatus;

                    // Wake up the requesting thread
                    synchronized (request) {
                        request.notifyAll();
                    }
                    break;

                case CMD_EXCHANGE_APDU:
                    request = (MainThreadRequest) msg.obj;
                    IccApduArgument argument = (IccApduArgument) request.argument;
                    sub = (Integer) request.argument2;
                    onCompleted = obtainMessage(EVENT_EXCHANGE_APDU_DONE, request);
                    getPhone(sub).getIccCard().exchangeApdu(argument.cla, argument.command,
                            argument.channel, argument.p1, argument.p2, argument.p3,
                            argument.data, onCompleted);
                    break;

                case EVENT_EXCHANGE_APDU_DONE:
                    ar = (AsyncResult) msg.obj;
                    request = (MainThreadRequest) ar.userObj;
                    sub = (Integer) request.argument2;

                    if (ar.exception == null && ar.result != null) {
                        request.result = ar.result;
                        mLastError[sub] = SUCCESS;
                    } else {
                        request.result = new IccIoResult(0x6f, 0, (byte[]) null);
                        mLastError[sub] = GENERIC_FAILURE;
                        if ((ar.exception != null) &&
                                (ar.exception instanceof CommandException)) {
                            if (((CommandException)ar.exception).getCommandError() ==
                                    CommandException.Error.INVALID_PARAMETER) {
                                mLastError[sub] = ERROR_INVALID_PARAMETER;
                            }
                        }
                    }

                    synchronized (request) {
                        request.notifyAll();
                    }
                    break;

                case CMD_OPEN_CHANNEL:
                    request = (MainThreadRequest) msg.obj;
                    sub = (Integer) request.argument2;
                    onCompleted = obtainMessage(EVENT_OPEN_CHANNEL_DONE, request);
                    getPhone(sub).getIccCard().openLogicalChannel((String)request.argument,
                            onCompleted);
                    break;

                case EVENT_OPEN_CHANNEL_DONE:
                    ar = (AsyncResult) msg.obj;
                    request = (MainThreadRequest) ar.userObj;
                    sub = (Integer) request.argument2;

                    if (ar.exception == null && ar.result != null) {
                        int[] resultArray = (int[]) ar.result;
                        request.result = new Integer(resultArray[0]);
                        mLastError[sub] = SUCCESS;
                    } else {
                        request.result = new Integer(RESULT_OPEN_CHANNEL_FAILURE);
                        mLastError[sub] = GENERIC_FAILURE;
                        if ((ar.exception != null) &&
                                (ar.exception instanceof CommandException)) {
                            if (((CommandException)ar.exception).getCommandError() ==
                                    CommandException.Error.MISSING_RESOURCE) {
                                mLastError[sub] = ERROR_MISSING_RESOURCE;
                            } else {
                                if (((CommandException)ar.exception).getCommandError() ==
                                    CommandException.Error.NO_SUCH_ELEMENT) {
                                    mLastError[sub] = ERROR_NO_SUCH_ELEMENT;
                                }
                            }
                        }
                    }

                    synchronized (request) {
                        request.notifyAll();
                    }
                    break;

                case CMD_CLOSE_CHANNEL:
                    request = (MainThreadRequest) msg.obj;
                    sub = (Integer) request.argument2;
                    onCompleted = obtainMessage(EVENT_CLOSE_CHANNEL_DONE, request);
                    getPhone(sub).getIccCard().closeLogicalChannel(
                            ((Integer)request.argument).intValue(), onCompleted);
                    break;

                case EVENT_CLOSE_CHANNEL_DONE:
                    ar = (AsyncResult) msg.obj;
                    request = (MainThreadRequest) ar.userObj;
                    sub = (Integer) request.argument2;

                    if (ar.exception == null) {
                        request.result = new Integer(SUCCESS);
                        mLastError[sub] = SUCCESS;
                    } else {
                        request.result = new Integer(RESULT_CLOSE_CHANNEL_FAILURE);
                        mLastError[sub] = GENERIC_FAILURE;
                        if ((ar.exception != null) && (ar.exception instanceof CommandException)) {
                            if (((CommandException)ar.exception).getCommandError() ==
                                    CommandException.Error.INVALID_PARAMETER) {
                                mLastError[sub] = ERROR_INVALID_PARAMETER;
                            }
                        }
                    }

                    synchronized (request) {
                        request.notifyAll();
                    }
                    break;

                case CMD_SIM_IO:
                    request = (MainThreadRequest) msg.obj;
                    IccApduArgument parameters =
                            (IccApduArgument) request.argument;
                    sub = (Integer) request.argument2;
                    onCompleted = obtainMessage(EVENT_SIM_IO_DONE, request);
                    getPhone(sub).getIccCard().exchangeIccIo( parameters.cla, /* fileID */
                            parameters.command, parameters.p1, parameters.p2, parameters.p3,
                            parameters.data, onCompleted);
                    break;

               case EVENT_SIM_IO_DONE:
                   ar = (AsyncResult) msg.obj;
                   request = (MainThreadRequest) ar.userObj;
                   sub = (Integer) request.argument2;
                   if (ar.exception == null && ar.result != null) {
                       request.result = ar.result;
                       mLastError[sub] = SUCCESS;
                   } else {
                       request.result = new IccIoResult(0x6f, 0, (byte[])null);
                       mLastError[sub] = GENERIC_FAILURE;
                       if ((ar.exception != null) &&
                               (ar.exception instanceof CommandException)) {
                           if (((CommandException)ar.exception).getCommandError() ==
                                    CommandException.Error.INVALID_PARAMETER) {
                               mLastError[sub] = ERROR_INVALID_PARAMETER;
                           }
                       }
                   }

                   synchronized (request) {
                        request.notifyAll();
                   }
                   break;

                case CMD_SIM_GET_ATR:
                    request = (MainThreadRequest) msg.obj;
                    sub = (Integer) request.argument2;
                    onCompleted = obtainMessage(EVENT_SIM_GET_ATR_DONE, request);
                    getPhone(sub).getIccCard().getAtr(onCompleted);
                    break;

                case EVENT_SIM_GET_ATR_DONE:
                    ar = (AsyncResult) msg.obj;
                    request = (MainThreadRequest) ar.userObj;
                    sub = (Integer) request.argument2;

                    if (ar.exception == null ) {
                        request.result = ar.result;
                        mLastError[sub] = SUCCESS;
                    } else {
                        request.result = "";
                        if ((ar.exception != null) &&
                                (ar.exception instanceof CommandException)) {
                            if (((CommandException)ar.exception)
                                    .getCommandError() ==
                                     CommandException.Error.RADIO_NOT_AVAILABLE) {
                                mLastError[sub] = GENERIC_FAILURE;
                            } else {
                                if (((CommandException)ar.exception).getCommandError() ==
                                        CommandException.Error.GENERIC_FAILURE) {
                                    mLastError[sub] = ERROR_MISSING_RESOURCE;
                                }
                            }
                        }
                    }

                    synchronized (request) {
                        request.notifyAll();
                    }
                    break;

                default:
                    Log.w(LOG_TAG, "MainThreadHandler: unexpected message code: " + msg.what);
                    break;
            }
        }
    }

    /**
     * Posts the specified command to be executed on the main thread,
     * waits for the request to complete, and returns the result.
     * @see sendRequestAsync
     */
    private Object sendRequest(int command, Object argument, Object argument2) {
        if (Looper.myLooper() == mMainThreadHandler.getLooper()) {
            throw new RuntimeException("This method will deadlock if called from the main thread.");
        }

        MainThreadRequest request = new MainThreadRequest(argument, argument2);
        Message msg = mMainThreadHandler.obtainMessage(command, request);
        msg.sendToTarget();

        // Wait for the request to complete
        synchronized (request) {
            while (request.result == null) {
                try {
                    request.wait();
                } catch (InterruptedException e) {
                    // Do nothing, go back and wait until the request is complete
                }
            }
        }
        return request.result;
    }

    /**
     * Asynchronous ("fire and forget") version of sendRequest():
     * Posts the specified command to be executed on the main thread, and
     * returns immediately.
     * @see sendRequest
     */
    private void sendRequestAsync(int command) {
        mMainThreadHandler.sendEmptyMessage(command);
    }

    /**
     * Initialize the singleton PhoneInterfaceManager instance.
     * This is only done once, at startup, from PhoneApp.onCreate().
     */
    /* package */ static MSimPhoneInterfaceManager init(PhoneGlobals app, Phone phone,
               CallHandlerServiceProxy callHandlerService) {
        synchronized (MSimPhoneInterfaceManager.class) {
            if (sInstance == null) {
                sInstance = new MSimPhoneInterfaceManager(app, phone, callHandlerService);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    /** Private constructor; @see init() */
    private MSimPhoneInterfaceManager(PhoneGlobals app, Phone phone,
            CallHandlerServiceProxy callHandlerService) {
        mApp = app;
        mPhone = phone;
        mCM = PhoneGlobals.getInstance().mCM;
        mMainThreadHandler = new MainThreadHandler();
        mCallHandlerService = callHandlerService;
        publish();
    }

    private void publish() {
        if (DBG) log("publish: " + this);

        ServiceManager.addService("phone_msim", this);
    }

    // returns phone associated with the subscription.
    // getPhone(0) returns default phone in single SIM mode.
    private Phone getPhone(int subscription) {
        return MSimPhoneGlobals.getInstance().getPhone(subscription);
    }

    //
    // Implementation of the ITelephony interface.
    //

    public void dial(String number, int subscription) {
        if (DBG) log("dial: " + number);
        // No permission check needed here: This is just a wrapper around the
        // ACTION_DIAL intent, which is available to any app since it puts up
        // the UI before it does anything.

        String url = createTelUrl(number);
        if (url == null) {
            return;
        }

        // PENDING: should we just silently fail if phone is offhook or ringing?
        PhoneConstants.State state = mCM.getState(subscription);
        if (state != PhoneConstants.State.OFFHOOK && state != PhoneConstants.State.RINGING) {
            Intent  intent = new Intent(Intent.ACTION_DIAL, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(SUBSCRIPTION_KEY, subscription);
            mApp.startActivity(intent);
        }
    }

    public void call(String callingPackage, String number, int subscription) {
        if (DBG) log("call: " + number);

        // This is just a wrapper around the ACTION_CALL intent, but we still
        // need to do a permission check since we're calling startActivity()
        // from the context of the phone app.
        enforceCallPermission();

        String url = createTelUrl(number);
        if (url == null) {
            return;
        }

        Intent intent = new Intent(Intent.ACTION_CALL, Uri.parse(url));
        intent.putExtra(SUBSCRIPTION_KEY, subscription);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mApp.startActivity(intent);
    }

    private boolean showCallScreenInternal(boolean specifyInitialDialpadState,
                                           boolean showDialpad) {
        if(MSimPhoneGlobals.getInstance().isCsvtActive()) {
            Log.d(LOG_TAG, "showCallScreenInternal: csvt is active");
            Intent mIntent = new Intent("restore_video_call");
            mApp.sendBroadcast(mIntent);
            return false;
        }
        if (!PhoneGlobals.sVoiceCapable) {
            // Never allow the InCallScreen to appear on data-only devices.
            return false;
        }
        int sub = mCM.getPhoneInCall().getSubscription();
        if (isIdle(sub)) {
            return false;
        }
        // If the phone isn't idle then go to the in-call screen
        long callingId = Binder.clearCallingIdentity();

        mCallHandlerService.bringToForeground(showDialpad);

        Binder.restoreCallingIdentity(callingId);

        return true;
    }

    // Show the in-call screen without specifying the initial dialpad state.
    public boolean showCallScreen() {
        return showCallScreenInternal(false, false);
    }

    // The variation of showCallScreen() that specifies the initial dialpad state.
    // (Ideally this would be called showCallScreen() too, just with a different
    // signature, but AIDL doesn't allow that.)
    public boolean showCallScreenWithDialpad(boolean showDialpad) {
        return showCallScreenInternal(true, showDialpad);
    }

    /**
     * End a call based on the call state of the subscription
     * @return true is a call was ended
     */
    public boolean endCall(int subscription) {
        enforceCallPermission();
        return (Boolean) sendRequest(CMD_END_CALL, subscription, null);
    }

    public void answerRingingCall(int subscription) {
        if (DBG) log("answerRingingCall...");
        // TODO: there should eventually be a separate "ANSWER_PHONE" permission,
        // but that can probably wait till the big TelephonyManager API overhaul.
        // For now, protect this call with the MODIFY_PHONE_STATE permission.
        enforceModifyPermission();
        sendRequestAsync(CMD_ANSWER_RINGING_CALL);
    }

    /**
     * Make the actual telephony calls to implement answerRingingCall().
     * This should only be called from the main thread of the Phone app.
     * @see answerRingingCall
     *
     * TODO: it would be nice to return true if we answered the call, or
     * false if there wasn't actually a ringing incoming call, or some
     * other error occurred.  (In other words, pass back the return value
     * from PhoneUtils.answerCall() or PhoneUtils.answerAndEndActive().)
     * But that would require calling this method via sendRequest() rather
     * than sendRequestAsync(), and right now we don't actually *need* that
     * return value, so let's just return void for now.
     */
    private void answerRingingCallInternal() {
        final boolean hasRingingCall = !mPhone.getRingingCall().isIdle();
        if (hasRingingCall) {
            final boolean hasActiveCall = !mPhone.getForegroundCall().isIdle();
            final boolean hasHoldingCall = !mPhone.getBackgroundCall().isIdle();
            if (hasActiveCall && hasHoldingCall) {
                // Both lines are in use!
                // TODO: provide a flag to let the caller specify what
                // policy to use if both lines are in use.  (The current
                // behavior is hardwired to "answer incoming, end ongoing",
                // which is how the CALL button is specced to behave.)
                PhoneUtils.answerAndEndActive(mCM, mCM.getFirstActiveRingingCall());
                return;
            } else {
                // answerCall() will automatically hold the current active
                // call, if there is one.
                PhoneUtils.answerCall(mCM.getFirstActiveRingingCall());
                return;
            }
        } else {
            // No call was ringing.
            return;
        }
    }

    public void silenceRinger() {
        if (DBG) log("silenceRinger...");
        // TODO: find a more appropriate permission to check here.
        // (That can probably wait till the big TelephonyManager API overhaul.
        // For now, protect this call with the MODIFY_PHONE_STATE permission.)
        enforceModifyPermission();
        sendRequestAsync(CMD_SILENCE_RINGER);
    }

    /**
     * Internal implemenation of silenceRinger().
     * This should only be called from the main thread of the Phone app.
     * @see silenceRinger
     */
    private void silenceRingerInternal() {
        if ((mCM.getState() == PhoneConstants.State.RINGING)
            && mApp.notifier.isRinging()) {
            // Ringer is actually playing, so silence it.
            if (DBG) log("silenceRingerInternal: silencing...");
            mApp.notifier.silenceRinger();
        }
    }

    public boolean isOffhook(int subscription) {
        return (getPhone(subscription).getState() == PhoneConstants.State.OFFHOOK);
    }

    public boolean isRinging(int subscription) {
        return (getPhone(subscription).getState() == PhoneConstants.State.RINGING);
    }

    public boolean isIdle(int subscription) {
        return (getPhone(subscription).getState() == PhoneConstants.State.IDLE);
    }

    public boolean isSimPinEnabled(int subscription) {
        enforceReadPermission();
        return ((MSimPhoneGlobals)mApp).isSimPinEnabled(subscription);
    }

    public boolean supplyPin(String pin, int subscription) {
        int [] resultArray = supplyPinReportResult(pin, subscription);
        return (resultArray[0] == PhoneConstants.PIN_RESULT_SUCCESS) ? true : false;
    }
    public boolean supplyPuk(String puk, String pin, int subscription) {
        int [] resultArray = supplyPukReportResult(puk, pin, subscription);
        return (resultArray[0] == PhoneConstants.PIN_RESULT_SUCCESS) ? true : false;
    }
    public int[] supplyPinReportResult(String pin, int subscription) {
        enforceModifyPermission();
        final UnlockSim checkSimPin = new UnlockSim(getPhone(subscription).getIccCard());
        checkSimPin.start();
        return checkSimPin.unlockSim(null, pin);
    }

    public int[] supplyPukReportResult(String puk, String pin, int subscription) {
        enforceModifyPermission();
        final UnlockSim checkSimPuk = new UnlockSim(getPhone(subscription).getIccCard());
        checkSimPuk.start();
        return checkSimPuk.unlockSim(puk, pin);
    }

    public int getIccPin1RetryCount(int subscription) {
        return getPhone(subscription).getIccCard().getIccPin1RetryCount();
    }

    /**
     * Helper thread to turn async call to {@link SimCard#supplyPin} into
     * a synchronous one.
     */
    private static class UnlockSim extends Thread {

        private final IccCard mSimCard;

        private boolean mDone = false;
        private int mResult = PhoneConstants.PIN_GENERAL_FAILURE;
        private int mRetryCount = -1;

        // For replies from SimCard interface
        private Handler mHandler;

        // For async handler to identify request type
        private static final int SUPPLY_PIN_COMPLETE = 100;

        public UnlockSim(IccCard simCard) {
            mSimCard = simCard;
        }

        @Override
        public void run() {
            Looper.prepare();
            synchronized (UnlockSim.this) {
                mHandler = new Handler() {
                    @Override
                    public void handleMessage(Message msg) {
                        AsyncResult ar = (AsyncResult) msg.obj;
                        switch (msg.what) {
                            case SUPPLY_PIN_COMPLETE:
                                Log.d(LOG_TAG, "SUPPLY_PIN_COMPLETE");
                                synchronized (UnlockSim.this) {
                                    mRetryCount = msg.arg1;
                                    if (ar.exception != null) {
                                        if (ar.exception instanceof CommandException &&
                                                ((CommandException)(ar.exception)).getCommandError()
                                                == CommandException.Error.PASSWORD_INCORRECT) {
                                            mResult = PhoneConstants.PIN_PASSWORD_INCORRECT;
                                        } else {
                                            mResult = PhoneConstants.PIN_GENERAL_FAILURE;
                                        }
                                    } else {
                                        mResult = PhoneConstants.PIN_RESULT_SUCCESS;
                                    }
                                    mDone = true;
                                    UnlockSim.this.notifyAll();
                                }
                                break;
                        }
                    }
                };
                UnlockSim.this.notifyAll();
            }
            Looper.loop();
        }

        /*
         * Use PIN or PUK to unlock SIM card
         *
         * If PUK is null, unlock SIM card with PIN
         *
         * If PUK is not null, unlock SIM card with PUK and set PIN code
         */
        synchronized int[] unlockSim(String puk, String pin) {

            while (mHandler == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Message callback = Message.obtain(mHandler, SUPPLY_PIN_COMPLETE);

            if (puk == null) {
                mSimCard.supplyPin(pin, callback);
            } else {
                mSimCard.supplyPuk(puk, pin, callback);
            }

            while (!mDone) {
                try {
                    Log.d(LOG_TAG, "wait for done");
                    wait();
                } catch (InterruptedException e) {
                    // Restore the interrupted status
                    Thread.currentThread().interrupt();
                }
            }
            Log.d(LOG_TAG, "done");
            int[] resultArray = new int[2];
            resultArray[0] = mResult;
            resultArray[1] = mRetryCount;
            return resultArray;
        }
    }

    public void updateServiceLocation(int subscription) {
        // No permission check needed here: this call is harmless, and it's
        // needed for the ServiceState.requestStateUpdate() call (which is
        // already intentionally exposed to 3rd parties.)
        getPhone(subscription).updateServiceLocation();
    }

    public boolean isRadioOn(int subscription) {
        return getPhone(subscription).getServiceState().getState() != ServiceState.STATE_POWER_OFF;
    }

    public void toggleRadioOnOff(int subscription) {
        enforceModifyPermission();
        getPhone(subscription).setRadioPower(!isRadioOn(subscription));
    }

    public boolean setRadio(boolean turnOn, int subscription) {
        enforceModifyPermission();
        if ((getPhone(subscription).getServiceState().getState() !=
                ServiceState.STATE_POWER_OFF) != turnOn) {
            toggleRadioOnOff(subscription);
        }
        return true;
    }

    public boolean enableDataConnectivity() {
        enforceModifyPermission();
        ConnectivityManager cm =
                (ConnectivityManager)mApp.getSystemService(Context.CONNECTIVITY_SERVICE);
        cm.setMobileDataEnabled(true);
        return true;
    }

    public int enableApnType(String type) {
        enforceModifyPermission();
        int result = PhoneConstants.APN_REQUEST_FAILED;
        int numPhones = MSimTelephonyManager.getDefault().getPhoneCount();
        int dds = ((MSimPhoneGlobals)mApp).getDataSubscription();
        for (int i = 0; i < numPhones; i++) {
            int ret = getPhone(i).enableApnType(type);
            if (i == dds) {
                result = ret;
                Log.d(LOG_TAG, "enableApnType result is " + result);
            }
        }
        return result;
    }

    public int disableApnType(String type) {
        enforceModifyPermission();
        int result = PhoneConstants.APN_REQUEST_FAILED;
        int numPhones = MSimTelephonyManager.getDefault().getPhoneCount();
        int dds = ((MSimPhoneGlobals)mApp).getDataSubscription();
        for (int i = 0; i < numPhones; i++) {
            int ret = getPhone(i).disableApnType(type);
            if (i == dds) {
                Log.d(LOG_TAG, "disableApnType result is " + result);
                result = ret;
            }
        }
        return result;
    }

    public boolean disableDataConnectivity() {
        enforceModifyPermission();
        ConnectivityManager cm =
                (ConnectivityManager)mApp.getSystemService(Context.CONNECTIVITY_SERVICE);
        cm.setMobileDataEnabled(false);
        return true;
    }

    public boolean isDataConnectivityPossible() {
        return getPhone(
                ((MSimPhoneGlobals)mApp).getDataSubscription()).isDataConnectivityPossible();
    }

    public boolean handlePinMmi(String dialString, int subscription) {
        enforceModifyPermission();
        return (Boolean) sendRequest(CMD_HANDLE_PIN_MMI, dialString, subscription);
    }

    public void cancelMissedCallsNotification(int subscription) {
        enforceModifyPermission();
        ((MSimNotificationMgr)(mApp.notificationMgr)).cancelMissedCallNotification();
    }

    public int getCallState(int subscription) {
        return DefaultPhoneNotifier.convertCallState(getPhone(subscription).getState());
    }

    public int getDataState() {
        return DefaultPhoneNotifier.convertDataState(
                getPhone(((MSimPhoneGlobals)mApp).getDataSubscription()).getDataConnectionState());
    }

    public int getDataActivity() {
        return DefaultPhoneNotifier.convertDataActivityState(
                getPhone(((MSimPhoneGlobals)mApp).getDataSubscription()).getDataActivityState());
    }

    public Bundle getCellLocation(int subscription) {
        try {
            mApp.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_FINE_LOCATION, null);
        } catch (SecurityException e) {
            // If we have ACCESS_FINE_LOCATION permission, skip the check for ACCESS_COARSE_LOCATION
            // A failure should throw the SecurityException from ACCESS_COARSE_LOCATION since this
            // is the weaker precondition
            mApp.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_COARSE_LOCATION, null);
        }
        Bundle data = new Bundle();
        getPhone(subscription).getCellLocation().fillInNotifierBundle(data);
        return data;
    }

    public void enableLocationUpdates(int subscription) {
        mApp.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONTROL_LOCATION_UPDATES, null);
        getPhone(subscription).enableLocationUpdates();
    }

    public void disableLocationUpdates(int subscription) {
        mApp.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONTROL_LOCATION_UPDATES, null);
        getPhone(subscription).disableLocationUpdates();
    }

    @SuppressWarnings("unchecked")
    public List<NeighboringCellInfo> getNeighboringCellInfo(int subscription) {
        try {
            mApp.enforceCallingOrSelfPermission(
                    android.Manifest.permission.ACCESS_FINE_LOCATION, null);
        } catch (SecurityException e) {
            // If we have ACCESS_FINE_LOCATION permission, skip the check
            // for ACCESS_COARSE_LOCATION
            // A failure should throw the SecurityException from
            // ACCESS_COARSE_LOCATION since this is the weaker precondition
            mApp.enforceCallingOrSelfPermission(
                    android.Manifest.permission.ACCESS_COARSE_LOCATION, null);
        }

        ArrayList<NeighboringCellInfo> cells = null;

        try {
            cells = (ArrayList<NeighboringCellInfo>) sendRequest(
                    CMD_HANDLE_NEIGHBORING_CELL, null, null);
        } catch (RuntimeException e) {
            Log.e(LOG_TAG, "getNeighboringCellInfo " + e);
        }

        return (List <NeighboringCellInfo>) cells;
    }

    public List<CellInfo> getAllCellInfo(int subscription) {
        try {
            mApp.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_FINE_LOCATION, null);
        } catch (SecurityException e) {
            // If we have ACCESS_FINE_LOCATION permission, skip the check for ACCESS_COARSE_LOCATION
            // A failure should throw the SecurityException from ACCESS_COARSE_LOCATION since this
            // is the weaker precondition
            mApp.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_COARSE_LOCATION, null);
        }

        return getPhone(subscription).getAllCellInfo();
    }

    //
    // Internal helper methods.
    //

    /**
     * Make sure the caller has the READ_PHONE_STATE permission.
     *
     * @throws SecurityException if the caller does not have the required permission
     */
    private void enforceReadPermission() {
        mApp.enforceCallingOrSelfPermission(android.Manifest.permission.READ_PHONE_STATE, null);
    }

    /**
     * Make sure the caller has the MODIFY_PHONE_STATE permission.
     *
     * @throws SecurityException if the caller does not have the required permission
     */
    private void enforceModifyPermission() {
        mApp.enforceCallingOrSelfPermission(android.Manifest.permission.MODIFY_PHONE_STATE, null);
    }

    /**
     * Make sure the caller has the CALL_PHONE permission.
     *
     * @throws SecurityException if the caller does not have the required permission
     */
    private void enforceCallPermission() {
        mApp.enforceCallingOrSelfPermission(android.Manifest.permission.CALL_PHONE, null);
    }


    private String createTelUrl(String number) {
        if (TextUtils.isEmpty(number)) {
            return null;
        }

        StringBuilder buf = new StringBuilder("tel:");
        buf.append(number);
        return buf.toString();
    }

    private void log(String msg) {
        Log.d(LOG_TAG, "[PhoneIntfMgr] " + msg);
    }

    public int getActivePhoneType() {
        return getActivePhoneType(getDefaultSubscription());
    }

    public int getActivePhoneType(int subscription) {
        return getPhone(subscription).getPhoneType();
    }

    /**
     * Returns the CDMA ERI icon index to display
     */
    public int getCdmaEriIconIndex() {
        return getCdmaEriIconIndex(getDefaultSubscription());
    }

    public int getCdmaEriIconIndex(int subscription) {
        return getPhone(subscription).getCdmaEriIconIndex();
    }

    /**
     * Returns the CDMA ERI icon mode,
     * 0 - ON
     * 1 - FLASHING
     */
    public int getCdmaEriIconMode() {
        return getCdmaEriIconMode(getDefaultSubscription());
    }

    public int getCdmaEriIconMode(int subscription) {
        return getPhone(subscription).getCdmaEriIconMode();
    }

    /**
     * Returns the CDMA ERI text,
     */
    public String getCdmaEriText() {
        return getCdmaEriText(getDefaultSubscription());
    }

    public String getCdmaEriText(int subscription) {
        return getPhone(subscription).getCdmaEriText();
    }

    /**
     * Returns true if CDMA provisioning needs to run.
     */
    public boolean needsOtaServiceProvisioning() {
        return mPhone.needsOtaServiceProvisioning();
    }

    /**
     * Returns the unread count of voicemails
     */
    public int getVoiceMessageCount() {
        return getVoiceMessageCount(getDefaultSubscription());
    }

    /**
     * Returns the unread count of voicemails for a subscription
     */
    public int getVoiceMessageCount(int subscription) {
        return getPhone(subscription).getVoiceMessageCount();
    }

    /**
     * Returns the network type
     */
    public int getNetworkType() {
        return getDataNetworkType(getPreferredDataSubscription());
    }

    /**
     * Returns the network type for a subscription
     */
    public int getNetworkType(int subscription) {
        return getDataNetworkType(subscription);
    }

    /**
     * Returns the data network type
     */
    public int getDataNetworkType() {
        return getDataNetworkType(getPreferredDataSubscription());
    }

    /**
     * Returns the data network type for a subscription
     */
    public int getDataNetworkType(int subscription) {
        return getPhone(subscription).getServiceState().getDataNetworkType();
    }

    /**
     * Returns the Voice network type
     */
    public int getVoiceNetworkType() {
        return getVoiceNetworkType(getDefaultSubscription());
    }

    /**
     * Returns the Voice network type for a subscription
     */
    public int getVoiceNetworkType(int subscription) {
        return getPhone(subscription).getServiceState().getVoiceNetworkType();
    }

    /**
     * @return true if a ICC card is present
     */
    public boolean hasIccCard() {
        return hasIccCard(getDefaultSubscription());
    }

    /**
     * @return true if a ICC card is present for a subscription
     */
    public boolean hasIccCard(int subscription) {
        return getPhone(subscription).getIccCard().hasIccCard();
    }


    /**
     * {@hide}
     * Returns Default subscription, 0 in the case of single standby.
     */
    public int getDefaultSubscription() {
        return mApp.getDefaultSubscription();
    }

    /**
     * {@hide}
     * Returns Preferred Voice subscription.
     */
    public int getPreferredVoiceSubscription() {
        return mApp.getVoiceSubscription();
    }

    /**
     * {@hide}
     * Returns Preferred Data subscription.
     */
    public int getPreferredDataSubscription() {
        return ((MSimPhoneGlobals)mApp).getDataSubscription();
    }

    /**
     * {@hide}
     * Returns default/user preferred Data subscription.
     */
    public int getDefaultDataSubscription() {
        return ((MSimPhoneGlobals)mApp).getDefaultDataSubscription();
    }

    /**
     * {@hide}
     * Set Data subscription retaining the default/user data sub preference.
     */
    public boolean setPreferredDataSubscription(int subscription) {
        return (Boolean) sendRequest(CMD_SET_DATA_SUBSCRIPTION, subscription, true);
    }

    /**
     * {@hide}
     * Set Data subscription. Also update the default/user preference.
     */
    public boolean setDefaultDataSubscription(int subscription) {
        return (Boolean) sendRequest(CMD_SET_DATA_SUBSCRIPTION, subscription, false);
    }

    /**
     * Return if the current radio is LTE on CDMA. This
     * is a tri-state return value as for a period of time
     * the mode may be unknown.
     *
     * @return {@link Phone#LTE_ON_CDMA_UNKNOWN}, {@link Phone#LTE_ON_CDMA_FALSE}
     * or {@link PHone#LTE_ON_CDMA_TRUE}
     */
    public int getLteOnCdmaMode() {
        return getLteOnCdmaMode(getDefaultSubscription());
    }

    public int getLteOnCdmaMode(int subscription) {
        return getPhone(subscription).getLteOnCdmaMode();
    }

    private String exchangeIccApdu( int cla, int command,
            int channel, int p1, int p2, int p3, String data, int subscription) {
        if (Binder.getCallingUid() != Process.NFC_UID) {
            throw new SecurityException("Only Smartcard API may access UICC");
        }

        Log.d(LOG_TAG, "> exchangeAPDU " + channel + " " + cla + " " +
                command + " " + p1 + " " + p2 + " " + p3 + " " + data + "subscription = "
                + subscription);
        IccIoResult response =
                (IccIoResult)sendRequest(CMD_EXCHANGE_APDU,
                        new IccApduArgument(cla, command, channel, p1, p2, p3, data), subscription);
        Log.d(LOG_TAG, "< exchangeAPDU " + response);

        String s = Integer.toHexString(
                (response.sw1 << 8) + response.sw2 + 0x10000).substring(1);
        if (response.payload != null) {
            s = IccUtils.bytesToHexString(response.payload) + s;
        }
        return s;
    }

    public String transmitIccBasicChannel(int cla, int command, int p1, int p2, int p3,
            String data, int subscription) {
        return exchangeIccApdu(cla, command, 0, p1, p2, p3, data, subscription);
    }

    public String transmitIccLogicalChannel(int cla, int command, int channel, int p1, int p2,
            int p3, String data, int subscription) {
        return exchangeIccApdu(cla, command, channel, p1, p2, p3, data, subscription);
    }

    public int openIccLogicalChannel(String aid, int subscription) {
        if (Binder.getCallingUid() != Process.NFC_UID) {
            throw new SecurityException("Only Smartcard API may access UICC");
        }

        Log.d(LOG_TAG, "> openIccLogicalChannel " + aid + " subscription = " + subscription);
        Integer channel = (Integer)sendRequest(CMD_OPEN_CHANNEL, aid, subscription);
        Log.d(LOG_TAG, "< openIccLogicalChannel " + channel);

        return channel.intValue();
    }

    public boolean closeIccLogicalChannel(int channel, int subscription) {
        if (Binder.getCallingUid() != Process.NFC_UID) {
            throw new SecurityException("Only Smartcard API may access UICC");
        }

        Log.d(LOG_TAG, "> closeIccLogicalChannel " + channel + " subscription = " + subscription);
        Integer err = (Integer)sendRequest(CMD_CLOSE_CHANNEL, new Integer(channel), subscription);
        Log.d(LOG_TAG, "< closeIccLogicalChannel " + err);

        if (err.intValue() == SUCCESS) {
            return true;
        }
        return false;
    }

    public byte[] transmitIccSimIO(int fileId, int command, int p1, int p2, int p3,
            String filePath, int subscription) {
        if (Binder.getCallingUid() != Process.NFC_UID) {
            throw new SecurityException("Only Smartcard API may access UICC");
        }

        Log.d(LOG_TAG, "Exchange SIM_IO " + fileId + ":" + command + " " + p1 + " " + p2 + " " +
                p3 + ":" + filePath + " " + "subscription = " + subscription);
        IccIoResult response = (IccIoResult)sendRequest(CMD_SIM_IO,
                new IccApduArgument(fileId, command, -1, p1, p2, p3, filePath), subscription);
        Log.d(LOG_TAG, "Exchange SIM_IO [R]" + response);

        byte[] result = null;
        int length = LEN_TWO_BYTE;
        if (response.payload != null) {
            length = LEN_TWO_BYTE + response.payload.length;
            result = new byte[length];
            System.arraycopy(response.payload, 0, result, 0, response.payload.length);
        } else {
            result = new byte[length];
        }
        Log.d(LOG_TAG,"Exchange SIM_IO [L] " + length);
        result[length - LEN_ONE_BYTE] = (byte)response.sw2;
        result[length - LEN_TWO_BYTE] = (byte)response.sw1;
        return result;
    }

    public byte[] getATR(int subscription) {
        if (Binder.getCallingUid() != Process.NFC_UID) {
            throw new SecurityException("Only Smartcard API may access UICC");
        }

        Log.d(LOG_TAG, "> getATR " + " subscription = " + subscription);
        String response = (String)sendRequest(CMD_SIM_GET_ATR, null, subscription);
        Log.d(LOG_TAG, "< getATR " + " response = " + response);

        byte[] result = null;
        if (response != null && response.length() != 0) {
            try{
                result = IccUtils.hexStringToBytes(response);
            } catch(RuntimeException re) {
                Log.e(LOG_TAG, "Invalid format of the response string");
            }
        }
        return result;
    }

    public int getLastError(int subscription) {
        return mLastError[subscription];
    }
}
