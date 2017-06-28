/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.telephony.imsphone;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.ResultReceiver;
import android.os.PowerManager.WakeLock;
import android.os.SystemProperties;
import android.os.UserHandle;

import android.telecom.VideoProfile;
import android.telephony.CarrierConfigManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.UssdResponse;
import android.text.TextUtils;

import com.android.ims.ImsCall;
import com.android.ims.ImsCallForwardInfo;
import com.android.ims.ImsCallProfile;
import com.android.ims.ImsEcbm;
import com.android.ims.ImsEcbmStateListener;
import com.android.ims.ImsException;
import com.android.ims.ImsManager;
import com.android.ims.ImsReasonInfo;
import com.android.ims.ImsSsInfo;
import com.android.ims.ImsStreamMediaProfile;
import com.android.ims.ImsUtInterface;

import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BAOC;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BAOIC;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BAOICxH;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BAIC;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BAICr;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BA_ALL;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BA_MO;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BA_MT;

import static com.android.internal.telephony.CommandsInterface.CF_ACTION_DISABLE;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_ENABLE;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_ERASURE;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_REGISTRATION;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_ALL;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_ALL_CONDITIONAL;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_NO_REPLY;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_NOT_REACHABLE;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_BUSY;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_UNCONDITIONAL;
import static com.android.internal.telephony.CommandsInterface.SERVICE_CLASS_DATA_SYNC;
import static com.android.internal.telephony.CommandsInterface.SERVICE_CLASS_PACKET;
import static com.android.internal.telephony.CommandsInterface.SERVICE_CLASS_VOICE;
import static com.android.internal.telephony.CommandsInterface.SERVICE_CLASS_NONE;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CallTracker;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.GsmCdmaPhone;
import com.android.internal.telephony.MmiCode;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneNotifier;
import com.android.internal.telephony.TelephonyComponentFactory;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.UUSInfo;
import com.android.internal.telephony.gsm.SuppServiceNotification;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.util.NotificationChannelController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.codeaurora.ims.QtiCallConstants;
import org.codeaurora.ims.utils.QtiImsExtUtils;

/**
 * {@hide}
 */
public class ImsPhone extends ImsPhoneBase {
    private static final String LOG_TAG = "ImsPhone";
    private static final boolean DBG = true;
    private static final boolean VDBG = false; // STOPSHIP if true

    private static final int EVENT_SET_CALL_BARRING_DONE             = EVENT_LAST + 1;
    private static final int EVENT_GET_CALL_BARRING_DONE             = EVENT_LAST + 2;
    private static final int EVENT_SET_CALL_WAITING_DONE             = EVENT_LAST + 3;
    private static final int EVENT_GET_CALL_WAITING_DONE             = EVENT_LAST + 4;
    private static final int EVENT_SET_CLIR_DONE                     = EVENT_LAST + 5;
    private static final int EVENT_GET_CLIR_DONE                     = EVENT_LAST + 6;
    private static final int EVENT_DEFAULT_PHONE_DATA_STATE_CHANGED  = EVENT_LAST + 7;
    private static final int EVENT_SERVICE_STATE_CHANGED             = EVENT_LAST + 8;
    private static final int EVENT_VOICE_CALL_ENDED                  = EVENT_LAST + 9;

    static final int RESTART_ECM_TIMER = 0; // restart Ecm timer
    static final int CANCEL_ECM_TIMER  = 1; // cancel Ecm timer

    // Default Emergency Callback Mode exit timer
    private static final int DEFAULT_ECM_EXIT_TIMER_VALUE = 300000;

    // Instance Variables
    Phone mDefaultPhone;
    ImsPhoneCallTracker mCT;
    ImsExternalCallTracker mExternalCallTracker;
    private ArrayList <ImsPhoneMmiCode> mPendingMMIs = new ArrayList<ImsPhoneMmiCode>();
    private ServiceState mSS = new ServiceState();

    // To redial silently through GSM or CDMA when dialing through IMS fails
    private String mLastDialString;

    private WakeLock mWakeLock;

    // mEcmExitRespRegistrant is informed after the phone has been exited the emergency
    // callback mode keep track of if phone is in emergency callback mode
    private Registrant mEcmExitRespRegistrant;

    private final RegistrantList mSilentRedialRegistrants = new RegistrantList();

    private boolean mImsRegistered = false;

    private boolean mRoaming = false;

    // List of Registrants to send supplementary service notifications to.
    private RegistrantList mSsnRegistrants = new RegistrantList();

    // A runnable which is used to automatically exit from Ecm after a period of time.
    private Runnable mExitEcmRunnable = new Runnable() {
        @Override
        public void run() {
            exitEmergencyCallbackMode();
        }
    };

    private Uri[] mCurrentSubscriberUris;

    protected void setCurrentSubscriberUris(Uri[] currentSubscriberUris) {
        this.mCurrentSubscriberUris = currentSubscriberUris;
    }

    @Override
    public Uri[] getCurrentSubscriberUris() {
        return mCurrentSubscriberUris;
    }

    // Create Cf (Call forward) so that dialling number &
    // mIsCfu (true if reason is call forward unconditional)
    // mOnComplete (Message object passed by client) can be packed &
    // given as a single Cf object as user data to UtInterface.
    private static class Cf {
        final String mSetCfNumber;
        final Message mOnComplete;
        final boolean mIsCfu;

        Cf(String cfNumber, boolean isCfu, Message onComplete) {
            mSetCfNumber = cfNumber;
            mIsCfu = isCfu;
            mOnComplete = onComplete;
        }
    }

    // Constructors
    public ImsPhone(Context context, PhoneNotifier notifier, Phone defaultPhone) {
        this(context, notifier, defaultPhone, false);
    }

    @VisibleForTesting
    public ImsPhone(Context context, PhoneNotifier notifier, Phone defaultPhone,
                    boolean unitTestMode) {
        super("ImsPhone", context, notifier, unitTestMode);

        mDefaultPhone = defaultPhone;
        // The ImsExternalCallTracker needs to be defined before the ImsPhoneCallTracker, as the
        // ImsPhoneCallTracker uses a thread to spool up the ImsManager.  Part of this involves
        // setting the multiendpoint listener on the external call tracker.  So we need to ensure
        // the external call tracker is available first to avoid potential timing issues.
        mExternalCallTracker =
                TelephonyComponentFactory.getInstance().makeImsExternalCallTracker(this);
        mCT = TelephonyComponentFactory.getInstance().makeImsPhoneCallTracker(this);
        mCT.registerPhoneStateListener(mExternalCallTracker);
        mExternalCallTracker.setCallPuller(mCT);

        mSS.setStateOff();

        mPhoneId = mDefaultPhone.getPhoneId();

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOG_TAG);
        mWakeLock.setReferenceCounted(false);

        if (mDefaultPhone.getServiceStateTracker() != null) {
            mDefaultPhone.getServiceStateTracker()
                    .registerForDataRegStateOrRatChanged(this,
                            EVENT_DEFAULT_PHONE_DATA_STATE_CHANGED, null);
        }
        updateDataServiceState();

        mDefaultPhone.registerForServiceStateChanged(this, EVENT_SERVICE_STATE_CHANGED, null);
        // Force initial roaming state update later, on EVENT_CARRIER_CONFIG_CHANGED.
        // Settings provider or CarrierConfig may not be loaded now.

        // Register receiver for sending RTT text message and
        // for receving RTT Operation
        // .i.e.Upgrade Initiate, Upgrade accept, Upgrade reject
        IntentFilter filter = new IntentFilter();
        filter.addAction(QtiCallConstants.ACTION_SEND_RTT_TEXT);
        filter.addAction(QtiCallConstants.ACTION_RTT_OPERATION);
        mDefaultPhone.getContext().registerReceiver(mRttReceiver, filter);
    }

    //todo: get rid of this function. It is not needed since parentPhone obj never changes
    @Override
    public void dispose() {
        Rlog.d(LOG_TAG, "dispose");
        // Nothing to dispose in Phone
        //super.dispose();
        mPendingMMIs.clear();
        mExternalCallTracker.tearDown();
        mCT.unregisterPhoneStateListener(mExternalCallTracker);
        mCT.unregisterForVoiceCallEnded(this);
        mCT.dispose();

        //Force all referenced classes to unregister their former registered events
        if (mDefaultPhone != null && mDefaultPhone.getServiceStateTracker() != null) {
            mDefaultPhone.getServiceStateTracker().
                    unregisterForDataRegStateOrRatChanged(this);
            mDefaultPhone.unregisterForServiceStateChanged(this);
            mDefaultPhone.getContext().unregisterReceiver(mRttReceiver);
        }
    }

    @Override
    public ServiceState
    getServiceState() {
        return mSS;
    }

    /* package */ void setServiceState(int state) {
        mSS.setVoiceRegState(state);
        updateDataServiceState();
    }

    @Override
    public CallTracker getCallTracker() {
        return mCT;
    }

    public void setVoiceCallForwardingFlag(int line, boolean enable, String number) {
        IccRecords r = getIccRecords();
        if (r != null) {
            setVoiceCallForwardingFlag(r, line, enable, number);
        }
    }

    public ImsExternalCallTracker getExternalCallTracker() {
        return mExternalCallTracker;
    }

    @Override
    public List<? extends ImsPhoneMmiCode>
    getPendingMmiCodes() {
        return mPendingMMIs;
    }

    @Override
    public void
    acceptCall(int videoState) throws CallStateException {
        mCT.acceptCall(videoState);
    }

    @Override
    public void
    rejectCall() throws CallStateException {
        mCT.rejectCall();
    }

    @Override
    public void
    switchHoldingAndActive() throws CallStateException {
        mCT.switchWaitingOrHoldingAndActive();
    }

    @Override
    public boolean canConference() {
        return mCT.canConference();
    }

    public boolean canDial() {
        return mCT.canDial();
    }

    @Override
    public void conference() {
        mCT.conference();
    }

    @Override
    public void clearDisconnected() {
        mCT.clearDisconnected();
    }

    @Override
    public boolean canTransfer() {
        return mCT.canTransfer();
    }

    @Override
    public void explicitCallTransfer() {
        mCT.explicitCallTransfer();
    }

    @Override
    public ImsPhoneCall
    getForegroundCall() {
        return mCT.mForegroundCall;
    }

    @Override
    public ImsPhoneCall
    getBackgroundCall() {
        return mCT.mBackgroundCall;
    }

    @Override
    public ImsPhoneCall
    getRingingCall() {
        return mCT.mRingingCall;
    }

    private boolean handleCallDeflectionIncallSupplementaryService(
            String dialString) {
        if (dialString.length() > 1) {
            return false;
        }

        if (getRingingCall().getState() != ImsPhoneCall.State.IDLE) {
            if (DBG) Rlog.d(LOG_TAG, "MmiCode 0: rejectCall");
            try {
                mCT.rejectCall();
            } catch (CallStateException e) {
                if (DBG) Rlog.d(LOG_TAG, "reject failed", e);
                notifySuppServiceFailed(Phone.SuppService.REJECT);
            }
        } else if (getBackgroundCall().getState() != ImsPhoneCall.State.IDLE) {
            if (DBG) Rlog.d(LOG_TAG, "MmiCode 0: hangupWaitingOrBackground");
            try {
                mCT.hangup(getBackgroundCall());
            } catch (CallStateException e) {
                if (DBG) Rlog.d(LOG_TAG, "hangup failed", e);
            }
        }

        return true;
    }

    private void sendUssdResponse(String ussdRequest, CharSequence message, int returnCode,
                                   ResultReceiver wrappedCallback) {
        UssdResponse response = new UssdResponse(ussdRequest, message);
        Bundle returnData = new Bundle();
        returnData.putParcelable(TelephonyManager.USSD_RESPONSE, response);
        wrappedCallback.send(returnCode, returnData);

    }

    @Override
    public boolean handleUssdRequest(String ussdRequest, ResultReceiver wrappedCallback)
            throws CallStateException {
        if (mPendingMMIs.size() > 0) {
            // There are MMI codes in progress; fail attempt now.
            Rlog.i(LOG_TAG, "handleUssdRequest: queue full: " + Rlog.pii(LOG_TAG, ussdRequest));
            sendUssdResponse(ussdRequest, null, TelephonyManager.USSD_RETURN_FAILURE,
                    wrappedCallback );
            return true;
        }
        try {
            dialInternal(ussdRequest, VideoProfile.STATE_AUDIO_ONLY, null, wrappedCallback);
        } catch (CallStateException cse) {
            if (CS_FALLBACK.equals(cse.getMessage())) {
                throw cse;
            } else {
                Rlog.w(LOG_TAG, "Could not execute USSD " + cse);
                sendUssdResponse(ussdRequest, null, TelephonyManager.USSD_RETURN_FAILURE,
                        wrappedCallback);
            }
        } catch (Exception e) {
            Rlog.w(LOG_TAG, "Could not execute USSD " + e);
            sendUssdResponse(ussdRequest, null, TelephonyManager.USSD_RETURN_FAILURE,
                    wrappedCallback);
            return false;
        }
        return true;
    }

    private boolean handleCallWaitingIncallSupplementaryService(
            String dialString) {
        int len = dialString.length();

        if (len > 2) {
            return false;
        }

        ImsPhoneCall call = getForegroundCall();

        try {
            if (len > 1) {
                if (DBG) Rlog.d(LOG_TAG, "not support 1X SEND");
                notifySuppServiceFailed(Phone.SuppService.HANGUP);
            } else {
                if (call.getState() != ImsPhoneCall.State.IDLE) {
                    if (DBG) Rlog.d(LOG_TAG, "MmiCode 1: hangup foreground");
                    mCT.hangup(call);
                } else {
                    if (DBG) Rlog.d(LOG_TAG, "MmiCode 1: switchWaitingOrHoldingAndActive");
                    mCT.switchWaitingOrHoldingAndActive();
                }
            }
        } catch (CallStateException e) {
            if (DBG) Rlog.d(LOG_TAG, "hangup failed", e);
            notifySuppServiceFailed(Phone.SuppService.HANGUP);
        }

        return true;
    }

    private boolean handleCallHoldIncallSupplementaryService(String dialString) {
        int len = dialString.length();

        if (len > 2) {
            return false;
        }

        if (len > 1) {
            if (DBG) Rlog.d(LOG_TAG, "separate not supported");
            notifySuppServiceFailed(Phone.SuppService.SEPARATE);
        } else {
            try {
                if (getRingingCall().getState() != ImsPhoneCall.State.IDLE) {
                    if (DBG) Rlog.d(LOG_TAG, "MmiCode 2: accept ringing call");
                    mCT.acceptCall(ImsCallProfile.CALL_TYPE_VOICE);
                } else {
                    if (DBG) Rlog.d(LOG_TAG, "MmiCode 2: switchWaitingOrHoldingAndActive");
                    mCT.switchWaitingOrHoldingAndActive();
                }
            } catch (CallStateException e) {
                if (DBG) Rlog.d(LOG_TAG, "switch failed", e);
                notifySuppServiceFailed(Phone.SuppService.SWITCH);
            }
        }

        return true;
    }

    private boolean handleMultipartyIncallSupplementaryService(
            String dialString) {
        if (dialString.length() > 1) {
            return false;
        }

        if (DBG) Rlog.d(LOG_TAG, "MmiCode 3: merge calls");
        conference();
        return true;
    }

    private boolean handleEctIncallSupplementaryService(String dialString) {

        int len = dialString.length();

        if (len != 1) {
            return false;
        }

        if (DBG) Rlog.d(LOG_TAG, "MmiCode 4: not support explicit call transfer");
        notifySuppServiceFailed(Phone.SuppService.TRANSFER);
        return true;
    }

    private boolean handleCcbsIncallSupplementaryService(String dialString) {
        if (dialString.length() > 1) {
            return false;
        }

        Rlog.i(LOG_TAG, "MmiCode 5: CCBS not supported!");
        // Treat it as an "unknown" service.
        notifySuppServiceFailed(Phone.SuppService.UNKNOWN);
        return true;
    }

    public void notifySuppSvcNotification(SuppServiceNotification suppSvc) {
        Rlog.d(LOG_TAG, "notifySuppSvcNotification: suppSvc = " + suppSvc);

        AsyncResult ar = new AsyncResult(null, suppSvc, null);
        mSsnRegistrants.notifyRegistrants(ar);
    }

    @Override
    public boolean handleInCallMmiCommands(String dialString) {
        if (!isInCall()) {
            return false;
        }

        if (TextUtils.isEmpty(dialString)) {
            return false;
        }

        boolean result = false;
        char ch = dialString.charAt(0);
        switch (ch) {
            case '0':
                result = handleCallDeflectionIncallSupplementaryService(
                        dialString);
                break;
            case '1':
                result = handleCallWaitingIncallSupplementaryService(
                        dialString);
                break;
            case '2':
                result = handleCallHoldIncallSupplementaryService(dialString);
                break;
            case '3':
                result = handleMultipartyIncallSupplementaryService(dialString);
                break;
            case '4':
                result = handleEctIncallSupplementaryService(dialString);
                break;
            case '5':
                result = handleCcbsIncallSupplementaryService(dialString);
                break;
            default:
                break;
        }

        return result;
    }

    boolean isInCall() {
        ImsPhoneCall.State foregroundCallState = getForegroundCall().getState();
        ImsPhoneCall.State backgroundCallState = getBackgroundCall().getState();
        ImsPhoneCall.State ringingCallState = getRingingCall().getState();

       return (foregroundCallState.isAlive() ||
               backgroundCallState.isAlive() ||
               ringingCallState.isAlive());
    }

    @Override
    public boolean isInEcm() {
        return mDefaultPhone.isInEcm();
    }

    @Override
    public void setIsInEcm(boolean isInEcm){
        mDefaultPhone.setIsInEcm(isInEcm);
    }

    public void notifyNewRingingConnection(Connection c) {
        mDefaultPhone.notifyNewRingingConnectionP(c);
    }

    void notifyUnknownConnection(Connection c) {
        mDefaultPhone.notifyUnknownConnectionP(c);
    }

    @Override
    public void notifyForVideoCapabilityChanged(boolean isVideoCapable) {
        mIsVideoCapable = isVideoCapable;
        mDefaultPhone.notifyForVideoCapabilityChanged(isVideoCapable);
    }

    @Override
    public Connection
    dial(String dialString, int videoState) throws CallStateException {
        return dialInternal(dialString, videoState, null, null);
    }

    @Override
    public Connection
    dial(String dialString, UUSInfo uusInfo, int videoState, Bundle intentExtras)
            throws CallStateException {
        // ignore UUSInfo
        return dialInternal (dialString, videoState, intentExtras, null);
    }

    protected Connection dialInternal(String dialString, int videoState, Bundle intentExtras)
            throws CallStateException {
        return dialInternal(dialString, videoState, intentExtras, null);
    }

    private Connection dialInternal(String dialString, int videoState,
                                    Bundle intentExtras, ResultReceiver wrappedCallback)
            throws CallStateException {
        boolean isConferenceUri = false;
        boolean isSkipSchemaParsing = false;
        if (intentExtras != null) {
            isConferenceUri = intentExtras.getBoolean(
                    TelephonyProperties.EXTRA_DIAL_CONFERENCE_URI, false);
            isSkipSchemaParsing = intentExtras.getBoolean(
                    TelephonyProperties.EXTRA_SKIP_SCHEMA_PARSING, false);
        }
        String newDialString = dialString;
        // Need to make sure dialString gets parsed properly.
        if (!isConferenceUri && !isSkipSchemaParsing) {
            newDialString = PhoneNumberUtils.stripSeparators(dialString);
        }

        // handle in-call MMI first if applicable
        if (handleInCallMmiCommands(newDialString)) {
            return null;
        }

        if (mDefaultPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            return mCT.dial(dialString, videoState, intentExtras);
        }

        // Only look at the Network portion for mmi
        String networkPortion = PhoneNumberUtils.extractNetworkPortionAlt(newDialString);
        ImsPhoneMmiCode mmi =
                ImsPhoneMmiCode.newFromDialString(networkPortion, this, wrappedCallback);
        if (DBG) Rlog.d(LOG_TAG,
                "dialInternal: dialing w/ mmi '" + mmi + "'...");

        if (mmi == null) {
            return mCT.dial(dialString, videoState, intentExtras);
        } else if (mmi.isTemporaryModeCLIR()) {
            return mCT.dial(mmi.getDialingNumber(), mmi.getCLIRMode(), videoState, intentExtras);
        } else if (!mmi.isSupportedOverImsPhone()) {
            // If the mmi is not supported by IMS service,
            // try to initiate dialing with default phone
            // Note: This code is never reached; there is a bug in isSupportedOverImsPhone which
            // causes it to return true even though the "processCode" method ultimately throws the
            // exception.
            Rlog.i(LOG_TAG, "dialInternal: USSD not supported by IMS; fallback to CS.");
            throw new CallStateException(CS_FALLBACK);
        } else {
            mPendingMMIs.add(mmi);
            mMmiRegistrants.notifyRegistrants(new AsyncResult(null, mmi, null));

            try {
                mmi.processCode();
            } catch (CallStateException cse) {
                if (CS_FALLBACK.equals(cse.getMessage())) {
                    Rlog.i(LOG_TAG, "dialInternal: fallback to GSM required.");
                    // Make sure we remove from the list of pending MMIs since it will handover to
                    // GSM.
                    mPendingMMIs.remove(mmi);
                    throw cse;
                }
            }

            return null;
        }
    }

    @Override
    public void addParticipant(String dialString) throws CallStateException {
        addParticipant(dialString, null);
    }

    @Override
    public void addParticipant(String dialString, Message onComplete) throws CallStateException {
        mCT.addParticipant(dialString, onComplete);
    }

    @Override
    public void
    sendDtmf(char c) {
        if (!PhoneNumberUtils.is12Key(c)) {
            Rlog.e(LOG_TAG,
                    "sendDtmf called with invalid character '" + c + "'");
        } else {
            if (mCT.getState() ==  PhoneConstants.State.OFFHOOK) {
                mCT.sendDtmf(c, null);
            }
        }
    }

    @Override
    public void
    startDtmf(char c) {
        if (!(PhoneNumberUtils.is12Key(c) || (c >= 'A' && c <= 'D'))) {
            Rlog.e(LOG_TAG,
                    "startDtmf called with invalid character '" + c + "'");
        } else {
            mCT.startDtmf(c);
        }
    }

    @Override
    public void
    stopDtmf() {
        mCT.stopDtmf();
    }

    public void notifyIncomingRing() {
        if (DBG) Rlog.d(LOG_TAG, "notifyIncomingRing");
        AsyncResult ar = new AsyncResult(null, null, null);
        sendMessage(obtainMessage(EVENT_CALL_RING, ar));
    }

    @Override
    public void setMute(boolean muted) {
        mCT.setMute(muted);
    }

    @Override
    public void setUiTTYMode(int uiTtyMode, Message onComplete) {
        mCT.setUiTTYMode(uiTtyMode, onComplete);
    }

    @Override
    public boolean getMute() {
        return mCT.getMute();
    }

    @Override
    public PhoneConstants.State getState() {
        return mCT.getState();
    }

    private boolean isValidCommandInterfaceCFReason (int commandInterfaceCFReason) {
        switch (commandInterfaceCFReason) {
        case CF_REASON_UNCONDITIONAL:
        case CF_REASON_BUSY:
        case CF_REASON_NO_REPLY:
        case CF_REASON_NOT_REACHABLE:
        case CF_REASON_ALL:
        case CF_REASON_ALL_CONDITIONAL:
            return true;
        default:
            return false;
        }
    }

    private boolean isValidCommandInterfaceCFAction (int commandInterfaceCFAction) {
        switch (commandInterfaceCFAction) {
        case CF_ACTION_DISABLE:
        case CF_ACTION_ENABLE:
        case CF_ACTION_REGISTRATION:
        case CF_ACTION_ERASURE:
            return true;
        default:
            return false;
        }
    }

    private  boolean isCfEnable(int action) {
        return (action == CF_ACTION_ENABLE) || (action == CF_ACTION_REGISTRATION);
    }

    private int getConditionFromCFReason(int reason) {
        switch(reason) {
            case CF_REASON_UNCONDITIONAL: return ImsUtInterface.CDIV_CF_UNCONDITIONAL;
            case CF_REASON_BUSY: return ImsUtInterface.CDIV_CF_BUSY;
            case CF_REASON_NO_REPLY: return ImsUtInterface.CDIV_CF_NO_REPLY;
            case CF_REASON_NOT_REACHABLE: return ImsUtInterface.CDIV_CF_NOT_REACHABLE;
            case CF_REASON_ALL: return ImsUtInterface.CDIV_CF_ALL;
            case CF_REASON_ALL_CONDITIONAL: return ImsUtInterface.CDIV_CF_ALL_CONDITIONAL;
            default:
                break;
        }

        return ImsUtInterface.INVALID;
    }

    private int getCFReasonFromCondition(int condition) {
        switch(condition) {
            case ImsUtInterface.CDIV_CF_UNCONDITIONAL: return CF_REASON_UNCONDITIONAL;
            case ImsUtInterface.CDIV_CF_BUSY: return CF_REASON_BUSY;
            case ImsUtInterface.CDIV_CF_NO_REPLY: return CF_REASON_NO_REPLY;
            case ImsUtInterface.CDIV_CF_NOT_REACHABLE: return CF_REASON_NOT_REACHABLE;
            case ImsUtInterface.CDIV_CF_ALL: return CF_REASON_ALL;
            case ImsUtInterface.CDIV_CF_ALL_CONDITIONAL: return CF_REASON_ALL_CONDITIONAL;
            default:
                break;
        }

        return CF_REASON_NOT_REACHABLE;
    }

    private int getActionFromCFAction(int action) {
        switch(action) {
            case CF_ACTION_DISABLE: return ImsUtInterface.ACTION_DEACTIVATION;
            case CF_ACTION_ENABLE: return ImsUtInterface.ACTION_ACTIVATION;
            case CF_ACTION_ERASURE: return ImsUtInterface.ACTION_ERASURE;
            case CF_ACTION_REGISTRATION: return ImsUtInterface.ACTION_REGISTRATION;
            default:
                break;
        }

        return ImsUtInterface.INVALID;
    }

    @Override
    public void getOutgoingCallerIdDisplay(Message onComplete) {
        if (DBG) Rlog.d(LOG_TAG, "getCLIR");
        Message resp;
        resp = obtainMessage(EVENT_GET_CLIR_DONE, onComplete);

        try {
            ImsUtInterface ut = mCT.getUtInterface();
            ut.queryCLIR(resp);
        } catch (ImsException e) {
            sendErrorResponse(onComplete, e);
        }
    }

    @Override
    public void setOutgoingCallerIdDisplay(int clirMode, Message onComplete) {
        if (DBG) Rlog.d(LOG_TAG, "setCLIR action= " + clirMode);
        Message resp;
        // Packing CLIR value in the message. This will be required for
        // SharedPreference caching, if the message comes back as part of
        // a success response.
        resp = obtainMessage(EVENT_SET_CLIR_DONE, clirMode, 0, onComplete);
        try {
            ImsUtInterface ut = mCT.getUtInterface();
            ut.updateCLIR(clirMode, resp);
        } catch (ImsException e) {
            sendErrorResponse(onComplete, e);
        }
    }

    @Override
    public void getCallForwardingOption(int commandInterfaceCFReason,
            Message onComplete) {
        getCallForwardingOption(commandInterfaceCFReason,
            SERVICE_CLASS_VOICE, onComplete);
    }

    @Override
    public void getCallForwardingOption(int commandInterfaceCFReason,
            int commandInterfaceServiceClass, Message onComplete) {
        if (DBG) Rlog.d(LOG_TAG, "getCallForwardingOption reason=" + commandInterfaceCFReason +
                "serviceclass =" + commandInterfaceServiceClass);
        if (isValidCommandInterfaceCFReason(commandInterfaceCFReason)) {
            if (DBG) Rlog.d(LOG_TAG, "requesting call forwarding query.");
            Message resp;
            resp = obtainMessage(EVENT_GET_CALL_FORWARD_DONE, onComplete);

            try {
                ImsUtInterface ut = mCT.getUtInterface();
                ut.queryCallForward(getConditionFromCFReason(commandInterfaceCFReason), null,
                        commandInterfaceServiceClass, resp);
            } catch (ImsException e) {
                sendErrorResponse(onComplete, e);
            }
        } else if (onComplete != null) {
             sendErrorResponse(onComplete);
        }
    }


    @Override
    public void setCallForwardingOption(int commandInterfaceCFAction,
            int commandInterfaceCFReason,
            String dialingNumber,
            int timerSeconds,
            Message onComplete) {
        setCallForwardingOption(commandInterfaceCFAction, commandInterfaceCFReason, dialingNumber,
                CommandsInterface.SERVICE_CLASS_VOICE, timerSeconds, onComplete);
    }

    @Override
    public void setCallForwardingOption(int commandInterfaceCFAction,
            int commandInterfaceCFReason,
            String dialingNumber,
            int serviceClass,
            int timerSeconds,
            Message onComplete) {
        if (DBG) Rlog.d(LOG_TAG, "setCallForwardingOption action=" + commandInterfaceCFAction
                + ", reason=" + commandInterfaceCFReason + " serviceClass=" + serviceClass);
        if ((isValidCommandInterfaceCFAction(commandInterfaceCFAction)) &&
                (isValidCommandInterfaceCFReason(commandInterfaceCFReason))) {
            Message resp;
            Cf cf = new Cf(dialingNumber,
                    (commandInterfaceCFReason == CF_REASON_UNCONDITIONAL ? true : false),
                    onComplete);
            resp = obtainMessage(EVENT_SET_CALL_FORWARD_DONE,
                    isCfEnable(commandInterfaceCFAction) ? 1 : 0, 0, cf);

            try {
                ImsUtInterface ut = mCT.getUtInterface();
                ut.updateCallForward(getActionFromCFAction(commandInterfaceCFAction),
                        getConditionFromCFReason(commandInterfaceCFReason),
                        dialingNumber,
                        serviceClass,
                        timerSeconds,
                        resp);
            } catch (ImsException e) {
                sendErrorResponse(onComplete, e);
            }
        } else if (onComplete != null) {
            sendErrorResponse(onComplete);
        }
    }

    @Override
    public void getCallWaiting(Message onComplete) {
        if (DBG) Rlog.d(LOG_TAG, "getCallWaiting");
        Message resp;
        resp = obtainMessage(EVENT_GET_CALL_WAITING_DONE, onComplete);

        try {
            ImsUtInterface ut = mCT.getUtInterface();
            ut.queryCallWaiting(resp);
        } catch (ImsException e) {
            sendErrorResponse(onComplete, e);
        }
    }

    @Override
    public void setCallWaiting(boolean enable, Message onComplete) {
        setCallWaiting(enable, CommandsInterface.SERVICE_CLASS_VOICE, onComplete);
    }

    public void setCallWaiting(boolean enable, int serviceClass, Message onComplete) {
        if (DBG) Rlog.d(LOG_TAG, "setCallWaiting enable=" + enable);
        Message resp;
        resp = obtainMessage(EVENT_SET_CALL_WAITING_DONE, onComplete);

        try {
            ImsUtInterface ut = mCT.getUtInterface();
            ut.updateCallWaiting(enable, serviceClass, resp);
        } catch (ImsException e) {
            sendErrorResponse(onComplete, e);
        }
    }

    private int getCBTypeFromFacility(String facility) {
        if (CB_FACILITY_BAOC.equals(facility)) {
            return ImsUtInterface.CB_BAOC;
        } else if (CB_FACILITY_BAOIC.equals(facility)) {
            return ImsUtInterface.CB_BOIC;
        } else if (CB_FACILITY_BAOICxH.equals(facility)) {
            return ImsUtInterface.CB_BOIC_EXHC;
        } else if (CB_FACILITY_BAIC.equals(facility)) {
            return ImsUtInterface.CB_BAIC;
        } else if (CB_FACILITY_BAICr.equals(facility)) {
            return ImsUtInterface.CB_BIC_WR;
        } else if (CB_FACILITY_BA_ALL.equals(facility)) {
            return ImsUtInterface.CB_BA_ALL;
        } else if (CB_FACILITY_BA_MO.equals(facility)) {
            return ImsUtInterface.CB_BA_MO;
        } else if (CB_FACILITY_BA_MT.equals(facility)) {
            return ImsUtInterface.CB_BA_MT;
        }

        return 0;
    }

    public void getCallBarring(String facility, Message onComplete) {
        getCallBarring(facility, CommandsInterface.SERVICE_CLASS_NONE, onComplete);
    }

    public void getCallBarring(String facility, int serviceClass, Message onComplete) {
        if (DBG) Rlog.d(LOG_TAG, "getCallBarring facility=" + facility +
                "serviceClass = " + serviceClass);
        Message resp;
        resp = obtainMessage(EVENT_GET_CALL_BARRING_DONE, onComplete);

        try {
            ImsUtInterface ut = mCT.getUtInterface();
            ut.queryCallBarring(getCBTypeFromFacility(facility), serviceClass, resp);
        } catch (ImsException e) {
            sendErrorResponse(onComplete, e);
        }
    }

    public void setCallBarring(String facility, boolean lockState, String password, Message
            onComplete) {
        setCallBarring(facility, lockState, CommandsInterface.SERVICE_CLASS_NONE,
                password, onComplete);
    }

    public void setCallBarring(String facility, boolean lockState, int serviceClass,
            String password, Message onComplete) {
        if (DBG) Rlog.d(LOG_TAG, "setCallBarring facility=" + facility
                + ", lockState=" + lockState + "serviceClass = " + serviceClass);
        Message resp;
        resp = obtainMessage(EVENT_SET_CALL_BARRING_DONE, onComplete);

        int action;
        if (lockState) {
            action = CommandsInterface.CF_ACTION_ENABLE;
        }
        else {
            action = CommandsInterface.CF_ACTION_DISABLE;
        }

        try {
            ImsUtInterface ut = mCT.getUtInterface();
            // password is not required with Ut interface
            ut.updateCallBarring(getCBTypeFromFacility(facility), action, serviceClass,
                    resp, null);
        } catch (ImsException e) {
            sendErrorResponse(onComplete, e);
        }
    }

    @Override
    public void sendUssdResponse(String ussdMessge) {
        Rlog.d(LOG_TAG, "sendUssdResponse");
        ImsPhoneMmiCode mmi = ImsPhoneMmiCode.newFromUssdUserInput(ussdMessge, this);
        mPendingMMIs.add(mmi);
        mMmiRegistrants.notifyRegistrants(new AsyncResult(null, mmi, null));
        mmi.sendUssd(ussdMessge);
    }

    public void sendUSSD(String ussdString, Message response) {
        mCT.sendUSSD(ussdString, response);
    }

    @Override
    public void cancelUSSD() {
        mCT.cancelUSSD();
    }

    private void sendErrorResponse(Message onComplete) {
        Rlog.d(LOG_TAG, "sendErrorResponse");
        if (onComplete != null) {
            AsyncResult.forMessage(onComplete, null,
                    new CommandException(CommandException.Error.GENERIC_FAILURE));
            onComplete.sendToTarget();
        }
    }

    @VisibleForTesting
    public void sendErrorResponse(Message onComplete, Throwable e) {
        Rlog.d(LOG_TAG, "sendErrorResponse");
        if (onComplete != null) {
            AsyncResult.forMessage(onComplete, null, getCommandException(e));
            onComplete.sendToTarget();
        }
    }

    private CommandException getCommandException(int code, String errorString) {
        Rlog.d(LOG_TAG, "getCommandException code= " + code
                + ", errorString= " + errorString);
        CommandException.Error error = CommandException.Error.GENERIC_FAILURE;

        switch(code) {
            case ImsReasonInfo.CODE_UT_NOT_SUPPORTED:
                error = CommandException.Error.REQUEST_NOT_SUPPORTED;
                break;
            case ImsReasonInfo.CODE_UT_CB_PASSWORD_MISMATCH:
                error = CommandException.Error.PASSWORD_INCORRECT;
                break;
            case ImsReasonInfo.CODE_UT_SERVICE_UNAVAILABLE:
                error = CommandException.Error.RADIO_NOT_AVAILABLE;
                break;
            case ImsReasonInfo.CODE_FDN_BLOCKED:
                error = CommandException.Error.FDN_CHECK_FAILURE;
            default:
                break;
        }

        return new CommandException(error, errorString);
    }

    private CommandException getCommandException(Throwable e) {
        CommandException ex = null;

        if (e instanceof ImsException) {
            ex = getCommandException(((ImsException)e).getCode(), e.getMessage());
        } else {
            Rlog.d(LOG_TAG, "getCommandException generic failure");
            ex = new CommandException(CommandException.Error.GENERIC_FAILURE);
        }
        return ex;
    }

    private void
    onNetworkInitiatedUssd(ImsPhoneMmiCode mmi) {
        Rlog.d(LOG_TAG, "onNetworkInitiatedUssd");
        mMmiCompleteRegistrants.notifyRegistrants(
            new AsyncResult(null, mmi, null));
    }

    /* package */
    void onIncomingUSSD(int ussdMode, String ussdMessage) {
        if (DBG) Rlog.d(LOG_TAG, "onIncomingUSSD ussdMode=" + ussdMode);

        boolean isUssdError;
        boolean isUssdRequest;

        isUssdRequest
            = (ussdMode == CommandsInterface.USSD_MODE_REQUEST);

        isUssdError
            = (ussdMode != CommandsInterface.USSD_MODE_NOTIFY
                && ussdMode != CommandsInterface.USSD_MODE_REQUEST);

        ImsPhoneMmiCode found = null;
        for (int i = 0, s = mPendingMMIs.size() ; i < s; i++) {
            if(mPendingMMIs.get(i).isPendingUSSD()) {
                found = mPendingMMIs.get(i);
                break;
            }
        }

        if (found != null) {
            // Complete pending USSD
            if (isUssdError) {
                found.onUssdFinishedError();
            } else {
                found.onUssdFinished(ussdMessage, isUssdRequest);
            }
        } else if (!isUssdError && ussdMessage != null) {
                // pending USSD not found
                // The network may initiate its own USSD request

                // ignore everything that isnt a Notify or a Request
                // also, discard if there is no message to present
                ImsPhoneMmiCode mmi;
                mmi = ImsPhoneMmiCode.newNetworkInitiatedUssd(ussdMessage,
                        isUssdRequest,
                        this);
                onNetworkInitiatedUssd(mmi);
        }
    }

    /**
     * Removes the given MMI from the pending list and notifies
     * registrants that it is complete.
     * @param mmi MMI that is done
     */
    public void onMMIDone(ImsPhoneMmiCode mmi) {
        /* Only notify complete if it's on the pending list.
         * Otherwise, it's already been handled (eg, previously canceled).
         * The exception is cancellation of an incoming USSD-REQUEST, which is
         * not on the list.
         */
        Rlog.d(LOG_TAG, "onMMIDone: mmi=" + mmi);
        if (mPendingMMIs.remove(mmi) || mmi.isUssdRequest()) {
            ResultReceiver receiverCallback = mmi.getUssdCallbackReceiver();
            if (receiverCallback != null) {
                int returnCode = (mmi.getState() ==  MmiCode.State.COMPLETE) ?
                        TelephonyManager.USSD_RETURN_SUCCESS : TelephonyManager.USSD_RETURN_FAILURE;
                sendUssdResponse(mmi.getDialString(), mmi.getMessage(), returnCode,
                        receiverCallback );
            } else {
                Rlog.v(LOG_TAG, "onMMIDone: notifyRegistrants");
                mMmiCompleteRegistrants.notifyRegistrants(
                    new AsyncResult(null, mmi, null));
            }
        }
    }

    @Override
    public ArrayList<Connection> getHandoverConnection() {
        ArrayList<Connection> connList = new ArrayList<Connection>();
        // Add all foreground call connections
        connList.addAll(getForegroundCall().mConnections);
        // Add all background call connections
        connList.addAll(getBackgroundCall().mConnections);
        // Add all background call connections
        connList.addAll(getRingingCall().mConnections);
        if (connList.size() > 0) {
            return connList;
        } else {
            return null;
        }
    }

    @Override
    public void notifySrvccState(Call.SrvccState state) {
        mCT.notifySrvccState(state);
    }

    /* package */ void
    initiateSilentRedial() {
        String result = mLastDialString;
        AsyncResult ar = new AsyncResult(null, result, null);
        if (ar != null) {
            mSilentRedialRegistrants.notifyRegistrants(ar);
        }
    }

    @Override
    public void registerForSilentRedial(Handler h, int what, Object obj) {
        mSilentRedialRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForSilentRedial(Handler h) {
        mSilentRedialRegistrants.remove(h);
    }

    @Override
    public void registerForSuppServiceNotification(Handler h, int what, Object obj) {
        mSsnRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForSuppServiceNotification(Handler h) {
        mSsnRegistrants.remove(h);
    }

    @Override
    public int getSubId() {
        return mDefaultPhone.getSubId();
    }

    @Override
    public int getPhoneId() {
        return mDefaultPhone.getPhoneId();
    }

    public IccRecords getIccRecords() {
        return mDefaultPhone.getIccRecords();
    }

    private CallForwardInfo getCallForwardInfo(ImsCallForwardInfo info) {
        CallForwardInfo cfInfo = new CallForwardInfo();
        cfInfo.status = info.mStatus;
        cfInfo.reason = getCFReasonFromCondition(info.mCondition);
        //Check if the service class signifies Video call forward
        if(info.mServiceClass == (SERVICE_CLASS_DATA_SYNC + SERVICE_CLASS_PACKET)) {
            cfInfo.serviceClass = info.mServiceClass;
        } else {
            cfInfo.serviceClass = SERVICE_CLASS_VOICE;
        }
        cfInfo.toa = info.mToA;
        cfInfo.number = info.mNumber;
        cfInfo.timeSeconds = info.mTimeSeconds;
        return cfInfo;
    }

    private CallForwardInfo[] handleCfQueryResult(ImsCallForwardInfo[] infos) {
        CallForwardInfo[] cfInfos = null;

        if (infos != null && infos.length != 0) {
            cfInfos = new CallForwardInfo[infos.length];
        }

        IccRecords r = mDefaultPhone.getIccRecords();
        if (infos == null || infos.length == 0) {
            if (r != null) {
                // Assume the default is not active
                // Set unconditional CFF in SIM to false
                setVoiceCallForwardingFlag(r, 1, false, null);
            }
        } else {
            for (int i = 0, s = infos.length; i < s; i++) {
                if (infos[i].mCondition == ImsUtInterface.CDIV_CF_UNCONDITIONAL) {
                    //Check if the service class signifies Video call forward
                    if (infos[i].mServiceClass == (SERVICE_CLASS_DATA_SYNC +
                                SERVICE_CLASS_PACKET)) {
                        setVideoCallForwardingPreference(infos[i].mStatus == 1);
                        notifyCallForwardingIndicator();
                    } else if (r != null) {
                        setVoiceCallForwardingFlag(r, 1, (infos[i].mStatus == 1),
                            infos[i].mNumber);
                    }
                }
                cfInfos[i] = getCallForwardInfo(infos[i]);
            }
        }

        return cfInfos;
    }

    private int[] handleCbQueryResult(ImsSsInfo[] infos) {
        int[] cbInfos = new int[1];
        cbInfos[0] = SERVICE_CLASS_NONE;

        if (infos[0].mStatus == 1) {
            cbInfos[0] = SERVICE_CLASS_VOICE;
        }

        return cbInfos;
    }

    private int[] handleCwQueryResult(ImsSsInfo[] infos) {
        int[] cwInfos = new int[2];
        cwInfos[0] = 0;

        if (infos[0].mStatus == 1) {
            cwInfos[0] = 1;
            cwInfos[1] = SERVICE_CLASS_VOICE;
        }

        return cwInfos;
    }

    private void
    sendResponse(Message onComplete, Object result, Throwable e) {
        if (onComplete != null) {
            CommandException ex = null;
            if (e != null) {
                ex = getCommandException(e);
            }
            AsyncResult.forMessage(onComplete, result, ex);
            onComplete.sendToTarget();
        }
    }

    private void updateDataServiceState() {
        if (mSS != null && mDefaultPhone.getServiceStateTracker() != null
                && mDefaultPhone.getServiceStateTracker().mSS != null) {
            ServiceState ss = mDefaultPhone.getServiceStateTracker().mSS;
            mSS.setDataRegState(ss.getDataRegState());
            mSS.setRilDataRadioTechnology(ss.getRilDataRadioTechnology());
            Rlog.d(LOG_TAG, "updateDataServiceState: defSs = " + ss + " imsSs = " + mSS);
        }
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;

        if (DBG) Rlog.d(LOG_TAG, "handleMessage what=" + msg.what);
        switch (msg.what) {
            case EVENT_SET_CALL_FORWARD_DONE:
                IccRecords r = mDefaultPhone.getIccRecords();
                Cf cf = (Cf) ar.userObj;
                if (cf.mIsCfu && ar.exception == null && r != null) {
                    setVoiceCallForwardingFlag(r, 1, msg.arg1 == 1, cf.mSetCfNumber);
                }
                sendResponse(cf.mOnComplete, null, ar.exception);
                break;

            case EVENT_GET_CALL_FORWARD_DONE:
                CallForwardInfo[] cfInfos = null;
                if (ar.exception == null) {
                    cfInfos = handleCfQueryResult((ImsCallForwardInfo[])ar.result);
                }
                sendResponse((Message) ar.userObj, cfInfos, ar.exception);
                break;

            case EVENT_GET_CALL_BARRING_DONE:
            case EVENT_GET_CALL_WAITING_DONE:
                int[] ssInfos = null;
                if (ar.exception == null) {
                    if (msg.what == EVENT_GET_CALL_BARRING_DONE) {
                        ssInfos = handleCbQueryResult((ImsSsInfo[])ar.result);
                    } else if (msg.what == EVENT_GET_CALL_WAITING_DONE) {
                        ssInfos = handleCwQueryResult((ImsSsInfo[])ar.result);
                    }
                }
                sendResponse((Message) ar.userObj, ssInfos, ar.exception);
                break;

            case EVENT_GET_CLIR_DONE:
                Bundle ssInfo = (Bundle) ar.result;
                int[] clirInfo = null;
                if (ssInfo != null) {
                    clirInfo = ssInfo.getIntArray(ImsPhoneMmiCode.UT_BUNDLE_KEY_CLIR);
                }
                sendResponse((Message) ar.userObj, clirInfo, ar.exception);
                break;

            case EVENT_SET_CLIR_DONE:
                if (ar.exception == null) {
                    saveClirSetting(msg.arg1);
                }
                 // (Intentional fallthrough)
            case EVENT_SET_CALL_BARRING_DONE:
            case EVENT_SET_CALL_WAITING_DONE:
                sendResponse((Message) ar.userObj, null, ar.exception);
                break;

            case EVENT_DEFAULT_PHONE_DATA_STATE_CHANGED:
                if (DBG) Rlog.d(LOG_TAG, "EVENT_DEFAULT_PHONE_DATA_STATE_CHANGED");
                updateDataServiceState();
                break;

            case EVENT_SERVICE_STATE_CHANGED:
                if (VDBG) Rlog.d(LOG_TAG, "EVENT_SERVICE_STATE_CHANGED");
                ar = (AsyncResult) msg.obj;
                ServiceState newServiceState = (ServiceState) ar.result;
                // only update if roaming status changed and voice or data is in service.
                // The STATE_IN_SERVICE is checked to prevent wifi calling mode change when phone
                // moves from roaming to no service.
                if (mRoaming != newServiceState.getRoaming() &&
                           (newServiceState.getVoiceRegState() == ServiceState.STATE_IN_SERVICE ||
                           newServiceState.getDataRegState() == ServiceState.STATE_IN_SERVICE)) {
                    if (DBG) Rlog.d(LOG_TAG, "Roaming state changed - " + mRoaming);
                    updateRoamingState(newServiceState.getRoaming());
                }
                break;
            case EVENT_VOICE_CALL_ENDED:
                if (DBG) Rlog.d(LOG_TAG, "Voice call ended. Handle pending updateRoamingState.");
                mCT.unregisterForVoiceCallEnded(this);
                // only update if roaming status changed
                boolean newRoaming = getCurrentRoaming();
                if (mRoaming != newRoaming) {
                    updateRoamingState(newRoaming);
                }
                break;

            default:
                super.handleMessage(msg);
                break;
        }
    }

    /**
     * Listen to the IMS ECBM state change
     */
    private ImsEcbmStateListener mImsEcbmStateListener =
            new ImsEcbmStateListener() {
                @Override
                public void onECBMEntered() {
                    if (DBG) Rlog.d(LOG_TAG, "onECBMEntered");
                    handleEnterEmergencyCallbackMode();
                }

                @Override
                public void onECBMExited() {
                    if (DBG) Rlog.d(LOG_TAG, "onECBMExited");
                    handleExitEmergencyCallbackMode();
                }
            };

    @VisibleForTesting
    public ImsEcbmStateListener getImsEcbmStateListener() {
        return mImsEcbmStateListener;
    }

    @Override
    public boolean isInEmergencyCall() {
        return mCT.isInEmergencyCall();
    }

    private void sendEmergencyCallbackModeChange() {
        // Send an Intent
        Intent intent = new Intent(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
        intent.putExtra(PhoneConstants.PHONE_IN_ECM_STATE, isInEcm());
        SubscriptionManager.putPhoneIdAndSubIdExtra(intent, getPhoneId());
        ActivityManager.broadcastStickyIntent(intent, UserHandle.USER_ALL);
        if (DBG) Rlog.d(LOG_TAG, "sendEmergencyCallbackModeChange");
    }

    @Override
    public void exitEmergencyCallbackMode() {
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        if (DBG) Rlog.d(LOG_TAG, "exitEmergencyCallbackMode()");

        // Send a message which will invoke handleExitEmergencyCallbackMode
        ImsEcbm ecbm;
        try {
            ecbm = mCT.getEcbmInterface();
            ecbm.exitEmergencyCallbackMode();
        } catch (ImsException e) {
            e.printStackTrace();
        }
    }

    private void handleEnterEmergencyCallbackMode() {
        if (DBG) {
            Rlog.d(LOG_TAG, "handleEnterEmergencyCallbackMode,mIsPhoneInEcmState= "
                    + isInEcm());
        }
        // if phone is not in Ecm mode, and it's changed to Ecm mode
        if (!isInEcm()) {
            setIsInEcm(true);
            // notify change
            sendEmergencyCallbackModeChange();

            // Post this runnable so we will automatically exit
            // if no one invokes exitEmergencyCallbackMode() directly.
            long delayInMillis = SystemProperties.getLong(
                    TelephonyProperties.PROPERTY_ECM_EXIT_TIMER, DEFAULT_ECM_EXIT_TIMER_VALUE);
            postDelayed(mExitEcmRunnable, delayInMillis);
            // We don't want to go to sleep while in Ecm
            mWakeLock.acquire();
        }
    }

    private void handleExitEmergencyCallbackMode() {
        if (DBG) {
            Rlog.d(LOG_TAG, "handleExitEmergencyCallbackMode: mIsPhoneInEcmState = "
                    + isInEcm());
        }

        if (isInEcm()) {
            setIsInEcm(false);
        }

        // Remove pending exit Ecm runnable, if any
        removeCallbacks(mExitEcmRunnable);

        if (mEcmExitRespRegistrant != null) {
            mEcmExitRespRegistrant.notifyResult(Boolean.TRUE);
        }

        // release wakeLock
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }

        // send an Intent
        sendEmergencyCallbackModeChange();
    }

    /**
     * Handle to cancel or restart Ecm timer in emergency call back mode if action is
     * CANCEL_ECM_TIMER, cancel Ecm timer and notify apps the timer is canceled; otherwise, restart
     * Ecm timer and notify apps the timer is restarted.
     */
    void handleTimerInEmergencyCallbackMode(int action) {
        switch (action) {
            case CANCEL_ECM_TIMER:
                removeCallbacks(mExitEcmRunnable);
                ((GsmCdmaPhone) mDefaultPhone).notifyEcbmTimerReset(Boolean.TRUE);
                break;
            case RESTART_ECM_TIMER:
                long delayInMillis = SystemProperties.getLong(
                        TelephonyProperties.PROPERTY_ECM_EXIT_TIMER, DEFAULT_ECM_EXIT_TIMER_VALUE);
                postDelayed(mExitEcmRunnable, delayInMillis);
                ((GsmCdmaPhone) mDefaultPhone).notifyEcbmTimerReset(Boolean.FALSE);
                break;
            default:
                Rlog.e(LOG_TAG, "handleTimerInEmergencyCallbackMode, unsupported action " + action);
        }
    }

    @Override
    public void setOnEcbModeExitResponse(Handler h, int what, Object obj) {
        mEcmExitRespRegistrant = new Registrant(h, what, obj);
    }

    @Override
    public void unsetOnEcbModeExitResponse(Handler h) {
        mEcmExitRespRegistrant.clear();
    }

    public void onFeatureCapabilityChanged() {
        mDefaultPhone.getServiceStateTracker().onImsCapabilityChanged();
    }

    @Override
    public boolean isVolteEnabled() {
        return mCT.isVolteEnabled();
    }

    @Override
    public boolean isWifiCallingEnabled() {
        return mCT.isVowifiEnabled();
    }

    @Override
    public boolean isVideoEnabled() {
        return mCT.isVideoCallEnabled();
    }

    @Override
    public Phone getDefaultPhone() {
        return mDefaultPhone;
    }

    @Override
    public boolean isImsRegistered() {
        return mImsRegistered;
    }

    public void setImsRegistered(boolean value) {
        mImsRegistered = value;
    }

    @Override
    public void callEndCleanupHandOverCallIfAny() {
        mCT.callEndCleanupHandOverCallIfAny();
    }

    private BroadcastReceiver mResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Add notification only if alert was not shown by WfcSettings
            if (getResultCode() == Activity.RESULT_OK) {
                // Default result code (as passed to sendOrderedBroadcast)
                // means that intent was not received by WfcSettings.

                CharSequence title = intent.getCharSequenceExtra(EXTRA_KEY_ALERT_TITLE);
                CharSequence messageAlert = intent.getCharSequenceExtra(EXTRA_KEY_ALERT_MESSAGE);
                CharSequence messageNotification = intent.getCharSequenceExtra(EXTRA_KEY_NOTIFICATION_MESSAGE);

                Intent resultIntent = new Intent(Intent.ACTION_MAIN);
                resultIntent.setClassName("com.android.settings",
                        "com.android.settings.Settings$WifiCallingSettingsActivity");
                resultIntent.putExtra(EXTRA_KEY_ALERT_SHOW, true);
                resultIntent.putExtra(EXTRA_KEY_ALERT_TITLE, title);
                resultIntent.putExtra(EXTRA_KEY_ALERT_MESSAGE, messageAlert);
                PendingIntent resultPendingIntent =
                        PendingIntent.getActivity(
                                mContext,
                                0,
                                resultIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT
                        );

                final Notification notification = new Notification.Builder(mContext)
                                .setSmallIcon(android.R.drawable.stat_sys_warning)
                                .setContentTitle(title)
                                .setContentText(messageNotification)
                                .setAutoCancel(true)
                                .setContentIntent(resultPendingIntent)
                                .setStyle(new Notification.BigTextStyle()
                                .bigText(messageNotification))
                                .setChannelId(NotificationChannelController.CHANNEL_ID_WFC)
                                .build();
                final String notificationTag = "wifi_calling";
                final int notificationId = 1;

                NotificationManager notificationManager =
                        (NotificationManager) mContext.getSystemService(
                                Context.NOTIFICATION_SERVICE);
                notificationManager.notify(notificationTag, notificationId,
                        notification);
            }
        }
    };

    /**
     * Show notification in case of some error codes.
     */
    public void processDisconnectReason(ImsReasonInfo imsReasonInfo) {
        if (imsReasonInfo.mCode == imsReasonInfo.CODE_REGISTRATION_ERROR
                && imsReasonInfo.mExtraMessage != null) {

            CarrierConfigManager configManager =
                    (CarrierConfigManager)mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
            if (configManager == null) {
                Rlog.e(LOG_TAG, "processDisconnectReason: CarrierConfigManager is not ready");
                return;
            }
            PersistableBundle pb = configManager.getConfigForSubId(getSubId());
            if (pb == null) {
                Rlog.e(LOG_TAG, "processDisconnectReason: no config for subId " + getSubId());
                return;
            }
            final String[] wfcOperatorErrorCodes =
                    pb.getStringArray(
                            CarrierConfigManager.KEY_WFC_OPERATOR_ERROR_CODES_STRING_ARRAY);
            if (wfcOperatorErrorCodes == null) {
                // no operator-specific error codes
                return;
            }

            final String[] wfcOperatorErrorAlertMessages =
                    mContext.getResources().getStringArray(
                            com.android.internal.R.array.wfcOperatorErrorAlertMessages);
            final String[] wfcOperatorErrorNotificationMessages =
                    mContext.getResources().getStringArray(
                            com.android.internal.R.array.wfcOperatorErrorNotificationMessages);

            for (int i = 0; i < wfcOperatorErrorCodes.length; i++) {
                String[] codes = wfcOperatorErrorCodes[i].split("\\|");
                if (codes.length != 2) {
                    Rlog.e(LOG_TAG, "Invalid carrier config: " + wfcOperatorErrorCodes[i]);
                    continue;
                }

                // Match error code.
                if (!imsReasonInfo.mExtraMessage.startsWith(
                        codes[0])) {
                    continue;
                }
                // If there is no delimiter at the end of error code string
                // then we need to verify that we are not matching partial code.
                // EXAMPLE: "REG9" must not match "REG99".
                // NOTE: Error code must not be empty.
                int codeStringLength = codes[0].length();
                char lastChar = codes[0].charAt(codeStringLength - 1);
                if (Character.isLetterOrDigit(lastChar)) {
                    if (imsReasonInfo.mExtraMessage.length() > codeStringLength) {
                        char nextChar = imsReasonInfo.mExtraMessage.charAt(codeStringLength);
                        if (Character.isLetterOrDigit(nextChar)) {
                            continue;
                        }
                    }
                }

                final CharSequence title = mContext.getText(
                        com.android.internal.R.string.wfcRegErrorTitle);

                int idx = Integer.parseInt(codes[1]);
                if (idx < 0 ||
                        idx >= wfcOperatorErrorAlertMessages.length ||
                        idx >= wfcOperatorErrorNotificationMessages.length) {
                    Rlog.e(LOG_TAG, "Invalid index: " + wfcOperatorErrorCodes[i]);
                    continue;
                }
                CharSequence messageAlert = imsReasonInfo.mExtraMessage;
                CharSequence messageNotification = imsReasonInfo.mExtraMessage;
                if (!wfcOperatorErrorAlertMessages[idx].isEmpty()) {
                    messageAlert = wfcOperatorErrorAlertMessages[idx];
                }
                if (!wfcOperatorErrorNotificationMessages[idx].isEmpty()) {
                    messageNotification = wfcOperatorErrorNotificationMessages[idx];
                }

                // UX requirement is to disable WFC in case of "permanent" registration failures.
                ImsManager.setWfcSetting(mContext, false);

                // If WfcSettings are active then alert will be shown
                // otherwise notification will be added.
                Intent intent = new Intent(ImsManager.ACTION_IMS_REGISTRATION_ERROR);
                intent.putExtra(EXTRA_KEY_ALERT_TITLE, title);
                intent.putExtra(EXTRA_KEY_ALERT_MESSAGE, messageAlert);
                intent.putExtra(EXTRA_KEY_NOTIFICATION_MESSAGE, messageNotification);
                mContext.sendOrderedBroadcast(intent, null, mResultReceiver,
                        null, Activity.RESULT_OK, null, null);

                // We can only match a single error code
                // so should break the loop after a successful match.
                break;
            }
        }
    }

    @Override
    public boolean isUtEnabled() {
        return mCT.isUtEnabled();
    }

    @Override
    public void sendEmergencyCallStateChange(boolean callActive) {
        mDefaultPhone.sendEmergencyCallStateChange(callActive);
    }

    @Override
    public void setBroadcastEmergencyCallStateChanges(boolean broadcast) {
        mDefaultPhone.setBroadcastEmergencyCallStateChanges(broadcast);
    }

    @Override
    public void notifyCallForwardingIndicator() {
        mDefaultPhone.notifyCallForwardingIndicator();
    }

    @VisibleForTesting
    public PowerManager.WakeLock getWakeLock() {
        return mWakeLock;
    }

    @Override
    public long getVtDataUsage() {
        return mCT.getVtDataUsage();
    }

    private void updateRoamingState(boolean newRoaming) {
        if (mCT.getState() == PhoneConstants.State.IDLE) {
            if (DBG) Rlog.d(LOG_TAG, "updateRoamingState now: " + newRoaming);
            mRoaming = newRoaming;
            ImsManager.setWfcMode(mContext,
                    ImsManager.getWfcMode(mContext, newRoaming), newRoaming);
        } else {
            if (DBG) Rlog.d(LOG_TAG, "updateRoamingState postponed: " + newRoaming);
            mCT.registerForVoiceCallEnded(this,
                    EVENT_VOICE_CALL_ENDED, null);
        }
    }

    private boolean getCurrentRoaming() {
        TelephonyManager tm = (TelephonyManager) mContext
                .getSystemService(Context.TELEPHONY_SERVICE);
        return tm.isNetworkRoaming();
    }

    private BroadcastReceiver mRttReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (QtiCallConstants.ACTION_SEND_RTT_TEXT.equals(intent.getAction())) {
                Rlog.d(LOG_TAG, "RTT: Received ACTION_SEND_RTT_TEXT");
                String data = intent.getStringExtra(QtiCallConstants.RTT_TEXT_VALUE);
                sendRttMessage(data);
            } else if (QtiCallConstants.ACTION_RTT_OPERATION.equals(intent.getAction())) {
                Rlog.d(LOG_TAG, "RTT: Received ACTION_RTT_OPERATION");
                int data = intent.getIntExtra(QtiCallConstants.RTT_OPERATION_TYPE, 0);
                checkIfModifyRequestOrResponse(data);
            } else {
                Rlog.d(LOG_TAG, "RTT: unknown intent");
            }
        }
    };

    /**
     * Sends Rtt message
     * Rtt Message can be sent only when -
     * operating mode is RTT_FULL and for non-VT calls only based on config
     *
     * @param data The Rtt text to be sent
     */
    public void sendRttMessage(String data) {
        if (!canProcessRttReqest() || !isFgCallActive()) {
            return;
        }

        // Check for empty message
        if (TextUtils.isEmpty(data)) {
            Rlog.d(LOG_TAG, "RTT: Text null");
            return;
        }

        ImsCall imsCall = getForegroundCall().getImsCall();
        if (imsCall == null) {
            Rlog.d(LOG_TAG, "RTT: imsCall null");
            return;
        }

        if (!isRttVtCallAllowed(imsCall)) {
            Rlog.d(LOG_TAG, "RTT: InCorrect mode");
            return;
        }

        Rlog.d(LOG_TAG, "RTT: sendRttMessage");
        try {
            imsCall.sendRttMessage(data);
        } catch (ImsException e) {
            Rlog.e(LOG_TAG, "RTT: sendRttMessage exception = " + e);
        }
    }

    /**
     * Sends RTT Upgrade request
     *
     * @param to: expected profile
     */
    public void sendRttModifyRequest(ImsCallProfile to) {
        Rlog.d(LOG_TAG, "RTT: sendRttModifyRequest");
        ImsCall imsCall = getForegroundCall().getImsCall();
        if (imsCall == null) {
            Rlog.d(LOG_TAG, "RTT: imsCall null");
            return;
        }

        try {
            imsCall.sendRttModifyRequest(to);
        } catch (ImsException e) {
            Rlog.e(LOG_TAG, "RTT: sendRttModifyRequest exception = " + e);
        }
    }

    /**
     * Sends RTT Upgrade response
     *
     * @param data : response for upgrade
     */
    public void sendRttModifyResponse(int response) {
        ImsCall imsCall = getForegroundCall().getImsCall();
        if (imsCall == null) {
            Rlog.d(LOG_TAG, "RTT: imsCall null");
            return;
        }

        if (!isRttVtCallAllowed(imsCall)) {
            Rlog.d(LOG_TAG, "RTT: Not allowed for VT");
            return;
        }

        Rlog.d(LOG_TAG, "RTT: sendRttModifyResponse");
        try {
            imsCall.sendRttModifyResponse(mapRequestToResponse(response));
        } catch (ImsException e) {
            Rlog.e(LOG_TAG, "RTT: sendRttModifyResponse exception = " + e);
        }
    }

    // Utility to check if the value coming in intent is for upgrade initiate or upgrade response
    private void checkIfModifyRequestOrResponse(int data) {
        if (!canProcessRttReqest() || !isFgCallActive()) {
            return;
        }

        Rlog.d(LOG_TAG, "RTT: checkIfModifyRequestOrResponse data =  " + data);
        switch (data) {
            case QtiCallConstants.RTT_UPGRADE_INITIATE:
                // Rtt Upgrade means enable Rtt
                packRttModifyRequestToProfile(ImsStreamMediaProfile.RTT_MODE_FULL);
                break;
            case QtiCallConstants.RTT_DOWNGRADE_INITIATE:
                // Rtt downrade means disable Rtt
                packRttModifyRequestToProfile(ImsStreamMediaProfile.RTT_MODE_DISABLED);
                break;
            case QtiCallConstants.RTT_UPGRADE_CONFIRM:
            case QtiCallConstants.RTT_UPGRADE_REJECT:
                sendRttModifyResponse(data);
                break;
        }
    }

    private void packRttModifyRequestToProfile(int data) {
        if (!canSendRttModifyRequest()) {
            Rlog.d(LOG_TAG, "RTT: cannot send rtt modify request");
            return;
        }

        ImsCallProfile fromProfile = getForegroundCall().getImsCall().getCallProfile();
        ImsCallProfile toProfile = fromProfile;
        toProfile.mMediaProfile = new ImsStreamMediaProfile(data);

        Rlog.d(LOG_TAG, "RTT: packRttModifyRequestToProfile");
        sendRttModifyRequest(toProfile);
    }

    private boolean canSendRttModifyRequest() {
        ImsCall imsCall = getForegroundCall().getImsCall();
        if (imsCall == null) {
            Rlog.d(LOG_TAG, "RTT: imsCall null");
            return false;
        }

        return true;
    }

    private boolean mapRequestToResponse(int response) {
        switch (response) {
            case QtiCallConstants.RTT_UPGRADE_CONFIRM:
                return true;
            case QtiCallConstants.RTT_UPGRADE_REJECT:
                return false;
            default:
                return false;
        }
    }

    /*
     * Returns true if Mode is RTT_FULL, false otherwise
     */
    private boolean isInFullRttMode() {
        int mode = QtiImsExtUtils.getRttOperatingMode(mContext);
        Rlog.d(LOG_TAG, "RTT: isInFullRttMode mode = " + mode);
        return (mode == QtiCallConstants.RTT_MODE_FULL);
    }

    /*
     * Rtt for VT calls is not supported for certain operators
     * Check the config and process the request
     */
    public boolean isRttVtCallAllowed(ImsCall call) {
        int mode = QtiImsExtUtils.getRttOperatingMode(mContext);
        Rlog.d(LOG_TAG, "RTT: isRttVtCallAllowed mode = " + mode);

        if (call.getCallProfile().isVideoCall() &&
                !QtiImsExtUtils.isRttSupportedOnVtCalls(mPhoneId, mContext)) {
            return false;
        }
        return true;
    }

    public boolean canProcessRttReqest() {
        // Process only if Carrier supports RTT and RTT is on
        if (!(QtiImsExtUtils.isRttSupported(mPhoneId, mContext) &&
                    QtiImsExtUtils.isRttOn(mContext))) {
            Rlog.d(LOG_TAG, "RTT: canProcessRttReqest RTT is not supported/off");
            return false;
        }
        Rlog.d(LOG_TAG, "RTT: canProcessRttReqest rtt supported = " +
                QtiImsExtUtils.isRttSupported(mPhoneId, mContext) + ", is Rtt on = " +
                QtiImsExtUtils.isRttOn(mContext) + ", Rtt mode = " +
                QtiImsExtUtils.getRttMode(mContext));
        return true;
    }

    public boolean isFgCallActive() {
        // process the request only if foreground is active
        if (ImsPhoneCall.State.ACTIVE != getForegroundCall().getState()) {
            Rlog.d(LOG_TAG, "RTT: isFgCallActive fg call not active");
            return false;
        }
        return true;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("ImsPhone extends:");
        super.dump(fd, pw, args);
        pw.flush();

        pw.println("ImsPhone:");
        pw.println("  mDefaultPhone = " + mDefaultPhone);
        pw.println("  mPendingMMIs = " + mPendingMMIs);
        pw.println("  mPostDialHandler = " + mPostDialHandler);
        pw.println("  mSS = " + mSS);
        pw.println("  mWakeLock = " + mWakeLock);
        pw.println("  mIsPhoneInEcmState = " + isInEcm());
        pw.println("  mEcmExitRespRegistrant = " + mEcmExitRespRegistrant);
        pw.println("  mSilentRedialRegistrants = " + mSilentRedialRegistrants);
        pw.println("  mImsRegistered = " + mImsRegistered);
        pw.println("  mRoaming = " + mRoaming);
        pw.println("  mSsnRegistrants = " + mSsnRegistrants);
        pw.flush();
    }
}
