/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.internal.telephony.uicc;

import android.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Message;
import android.telephony.Rlog;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.gsm.SimTlv;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * {@hide}
 */
public class IsimUiccRecords extends IccRecords implements IsimRecords {
    protected static final String LOG_TAG = "IsimUiccRecords";

    private static final boolean DBG = true;
    private static final boolean VDBG = false; // STOPSHIP if true
    private static final boolean DUMP_RECORDS = false;  // Note: PII is logged when this is true
                                                        // STOPSHIP if true
    public static final String INTENT_ISIM_REFRESH = "com.android.intent.isim_refresh";

    private static final int EVENT_APP_READY = 1;
    private static final int EVENT_ISIM_AUTHENTICATE_DONE          = 91;

    // ISIM EF records (see 3GPP TS 31.103)
    @UnsupportedAppUsage
    private String mIsimImpi;               // IMS private user identity
    @UnsupportedAppUsage
    private String mIsimDomain;             // IMS home network domain name
    @UnsupportedAppUsage
    private String[] mIsimImpu;             // IMS public user identity(s)
    @UnsupportedAppUsage
    private String mIsimIst;                // IMS Service Table
    @UnsupportedAppUsage
    private String[] mIsimPcscf;            // IMS Proxy Call Session Control Function
    @UnsupportedAppUsage
    private String auth_rsp;

    @UnsupportedAppUsage
    private final Object mLock = new Object();

    private static final int TAG_ISIM_VALUE = 0x80;     // From 3GPP TS 31.103

    @Override
    public String toString() {
        return "IsimUiccRecords: " + super.toString()
                + (DUMP_RECORDS ? (" mIsimImpi=" + mIsimImpi
                + " mIsimDomain=" + mIsimDomain
                + " mIsimImpu=" + mIsimImpu
                + " mIsimIst=" + mIsimIst
                + " mIsimPcscf=" + mIsimPcscf) : "");
    }

    public IsimUiccRecords(UiccCardApplication app, Context c, CommandsInterface ci) {
        super(app, c, ci);

        mRecordsRequested = false;  // No load request is made till SIM ready
        //todo: currently locked state for ISIM is not handled well and may cause app state to not
        //be broadcast
        mLockedRecordsReqReason = LOCKED_RECORDS_REQ_REASON_NONE;

        // recordsToLoad is set to 0 because no requests are made yet
        mRecordsToLoad = 0;
        // Start off by setting empty state
        resetRecords();
        if (DBG) log("IsimUiccRecords X ctor this=" + this);
    }

    @Override
    public void dispose() {
        log("Disposing " + this);
        resetRecords();
        super.dispose();
    }

    // ***** Overridden from Handler
    public void handleMessage(Message msg) {
        AsyncResult ar;

        if (mDestroyed.get()) {
            Rlog.e(LOG_TAG, "Received message " + msg +
                    "[" + msg.what + "] while being destroyed. Ignoring.");
            return;
        }
        loge("IsimUiccRecords: handleMessage " + msg + "[" + msg.what + "] ");

        try {
            switch (msg.what) {
                case EVENT_REFRESH:
                    broadcastRefresh();
                    super.handleMessage(msg);
                    break;

                case EVENT_ISIM_AUTHENTICATE_DONE:
                    ar = (AsyncResult)msg.obj;
                    log("EVENT_ISIM_AUTHENTICATE_DONE");
                    if (ar.exception != null) {
                        log("Exception ISIM AKA: " + ar.exception);
                    } else {
                        try {
                            auth_rsp = (String)ar.result;
                            log("ISIM AKA: auth_rsp = " + auth_rsp);
                        } catch (Exception e) {
                            log("Failed to parse ISIM AKA contents: " + e);
                        }
                    }
                    synchronized (mLock) {
                        mLock.notifyAll();
                    }

                    break;

                default:
                    super.handleMessage(msg);   // IccRecords handles generic record load responses

            }
        } catch (RuntimeException exc) {
            // I don't want these exceptions to be fatal
            Rlog.w(LOG_TAG, "Exception parsing SIM record", exc);
        }
    }

    @UnsupportedAppUsage
    protected void fetchIsimRecords() {
        mRecordsRequested = true;

        mFh.loadEFTransparent(EF_IMPI, obtainMessage(
                IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimImpiLoaded()));
        mRecordsToLoad++;

        mFh.loadEFLinearFixedAll(EF_IMPU, obtainMessage(
                IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimImpuLoaded()));
        mRecordsToLoad++;

        mFh.loadEFTransparent(EF_DOMAIN, obtainMessage(
                IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimDomainLoaded()));
        mRecordsToLoad++;
        mFh.loadEFTransparent(EF_IST, obtainMessage(
                    IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimIstLoaded()));
        mRecordsToLoad++;
        mFh.loadEFLinearFixedAll(EF_PCSCF, obtainMessage(
                    IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimPcscfLoaded()));
        mRecordsToLoad++;

        if (DBG) log("fetchIsimRecords " + mRecordsToLoad + " requested: " + mRecordsRequested);
    }

    protected void resetRecords() {
        // recordsRequested is set to false indicating that the SIM
        // read requests made so far are not valid. This is set to
        // true only when fresh set of read requests are made.
        mIsimImpi = null;
        mIsimDomain = null;
        mIsimImpu = null;
        mIsimIst = null;
        mIsimPcscf = null;
        auth_rsp = null;

        mRecordsRequested = false;
        mLockedRecordsReqReason = LOCKED_RECORDS_REQ_REASON_NONE;
        mLoaded.set(false);
    }

    private class EfIsimImpiLoaded implements IccRecords.IccRecordLoaded {
        public String getEfName() {
            return "EF_ISIM_IMPI";
        }
        public void onRecordLoaded(AsyncResult ar) {
            byte[] data = (byte[]) ar.result;
            mIsimImpi = isimTlvToString(data);
            if (DUMP_RECORDS) log("EF_IMPI=" + mIsimImpi);
        }
    }

    private class EfIsimImpuLoaded implements IccRecords.IccRecordLoaded {
        public String getEfName() {
            return "EF_ISIM_IMPU";
        }
        public void onRecordLoaded(AsyncResult ar) {
            ArrayList<byte[]> impuList = (ArrayList<byte[]>) ar.result;
            if (DBG) log("EF_IMPU record count: " + impuList.size());
            mIsimImpu = new String[impuList.size()];
            int i = 0;
            for (byte[] identity : impuList) {
                String impu = isimTlvToString(identity);
                if (DUMP_RECORDS) log("EF_IMPU[" + i + "]=" + impu);
                mIsimImpu[i++] = impu;
            }
        }
    }

    private class EfIsimDomainLoaded implements IccRecords.IccRecordLoaded {
        public String getEfName() {
            return "EF_ISIM_DOMAIN";
        }
        public void onRecordLoaded(AsyncResult ar) {
            byte[] data = (byte[]) ar.result;
            mIsimDomain = isimTlvToString(data);
            if (DUMP_RECORDS) log("EF_DOMAIN=" + mIsimDomain);
        }
    }

    private class EfIsimIstLoaded implements IccRecords.IccRecordLoaded {
        public String getEfName() {
            return "EF_ISIM_IST";
        }
        public void onRecordLoaded(AsyncResult ar) {
            byte[] data = (byte[]) ar.result;
            mIsimIst = IccUtils.bytesToHexString(data);
            if (DUMP_RECORDS) log("EF_IST=" + mIsimIst);
        }
    }
    private class EfIsimPcscfLoaded implements IccRecords.IccRecordLoaded {
        public String getEfName() {
            return "EF_ISIM_PCSCF";
        }
        public void onRecordLoaded(AsyncResult ar) {
            ArrayList<byte[]> pcscflist = (ArrayList<byte[]>) ar.result;
            if (DBG) log("EF_PCSCF record count: " + pcscflist.size());
            mIsimPcscf = new String[pcscflist.size()];
            int i = 0;
            for (byte[] identity : pcscflist) {
                String pcscf = isimTlvToString(identity);
                if (DUMP_RECORDS) log("EF_PCSCF[" + i + "]=" + pcscf);
                mIsimPcscf[i++] = pcscf;
            }
        }
    }

    /**
     * ISIM records for IMS are stored inside a Tag-Length-Value record as a UTF-8 string
     * with tag value 0x80.
     * @param record the byte array containing the IMS data string
     * @return the decoded String value, or null if the record can't be decoded
     */
    @UnsupportedAppUsage
    private static String isimTlvToString(byte[] record) {
        SimTlv tlv = new SimTlv(record, 0, record.length);
        do {
            if (tlv.getTag() == TAG_ISIM_VALUE) {
                return new String(tlv.getData(), Charset.forName("UTF-8"));
            }
        } while (tlv.nextObject());

        if (VDBG) {
            Rlog.d(LOG_TAG, "[ISIM] can't find TLV. record = " + IccUtils.bytesToHexString(record));
        }
        return null;
    }

    @Override
    protected void onRecordLoaded() {
        // One record loaded successfully or failed, In either case
        // we need to update the recordsToLoad count
        mRecordsToLoad -= 1;
        if (DBG) log("onRecordLoaded " + mRecordsToLoad + " requested: " + mRecordsRequested);

        if (getRecordsLoaded()) {
            onAllRecordsLoaded();
        } else if (getLockedRecordsLoaded() || getNetworkLockedRecordsLoaded()) {
            onLockedAllRecordsLoaded();
        } else if (mRecordsToLoad < 0) {
            loge("recordsToLoad <0, programmer error suspected");
            mRecordsToLoad = 0;
        }
    }

    private void onLockedAllRecordsLoaded() {
        if (DBG) log("SIM locked; record load complete");
        if (mLockedRecordsReqReason == LOCKED_RECORDS_REQ_REASON_LOCKED) {
            mLockedRecordsLoadedRegistrants.notifyRegistrants(new AsyncResult(null, null, null));
        } else if (mLockedRecordsReqReason == LOCKED_RECORDS_REQ_REASON_NETWORK_LOCKED) {
            mNetworkLockedRecordsLoadedRegistrants.notifyRegistrants(
                    new AsyncResult(null, null, null));
        } else {
            loge("onLockedAllRecordsLoaded: unexpected mLockedRecordsReqReason "
                    + mLockedRecordsReqReason);
        }
    }

    @Override
    protected void onAllRecordsLoaded() {
       if (DBG) log("record load complete");
        mLoaded.set(true);
        mRecordsLoadedRegistrants.notifyRegistrants(new AsyncResult(null, null, null));
    }

    @Override
    protected void handleFileUpdate(int efid) {
        switch (efid) {
            case EF_IMPI:
                mFh.loadEFTransparent(EF_IMPI, obtainMessage(
                            IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimImpiLoaded()));
                mRecordsToLoad++;
                break;

            case EF_IMPU:
                mFh.loadEFLinearFixedAll(EF_IMPU, obtainMessage(
                            IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimImpuLoaded()));
                mRecordsToLoad++;
            break;

            case EF_DOMAIN:
                mFh.loadEFTransparent(EF_DOMAIN, obtainMessage(
                            IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimDomainLoaded()));
                mRecordsToLoad++;
            break;

            case EF_IST:
                mFh.loadEFTransparent(EF_IST, obtainMessage(
                            IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimIstLoaded()));
                mRecordsToLoad++;
            break;

            case EF_PCSCF:
                mFh.loadEFLinearFixedAll(EF_PCSCF, obtainMessage(
                            IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimPcscfLoaded()));
                mRecordsToLoad++;

            default:
                fetchIsimRecords();
                break;
        }
    }

    private void broadcastRefresh() {
        Intent intent = new Intent(INTENT_ISIM_REFRESH);
        log("send ISim REFRESH: " + INTENT_ISIM_REFRESH);
        intent.putExtra(PhoneConstants.PHONE_KEY, mParentApp.getPhoneId());
        mContext.sendBroadcast(intent);
    }

    /**
     * Return the IMS private user identity (IMPI).
     * Returns null if the IMPI hasn't been loaded or isn't present on the ISIM.
     * @return the IMS private user identity string, or null if not available
     */
    @Override
    public String getIsimImpi() {
        return mIsimImpi;
    }

    /**
     * Return the IMS home network domain name.
     * Returns null if the IMS domain hasn't been loaded or isn't present on the ISIM.
     * @return the IMS home network domain name, or null if not available
     */
    @Override
    public String getIsimDomain() {
        return mIsimDomain;
    }

    /**
     * Return an array of IMS public user identities (IMPU).
     * Returns null if the IMPU hasn't been loaded or isn't present on the ISIM.
     * @return an array of IMS public user identity strings, or null if not available
     */
    @Override
    public String[] getIsimImpu() {
        return (mIsimImpu != null) ? mIsimImpu.clone() : null;
    }

    /**
     * Returns the IMS Service Table (IST) that was loaded from the ISIM.
     * @return IMS Service Table or null if not present or not loaded
     */
    @Override
    public String getIsimIst() {
        return mIsimIst;
    }

    /**
     * Returns the IMS Proxy Call Session Control Function(PCSCF) that were loaded from the ISIM.
     * @return an array of  PCSCF strings with one PCSCF per string, or null if
     *      not present or not loaded
     */
    @Override
    public String[] getIsimPcscf() {
        return (mIsimPcscf != null) ? mIsimPcscf.clone() : null;
    }

    @Override
    public void onReady() {
        fetchIsimRecords();
    }

    @Override
    public void onRefresh(boolean fileChanged, int[] fileList) {
        if (fileChanged) {
            // A future optimization would be to inspect fileList and
            // only reload those files that we care about.  For now,
            // just re-fetch all SIM records that we cache.
            fetchIsimRecords();
        }
    }

    @Override
    public void setVoiceMailNumber(String alphaTag, String voiceNumber,
            Message onComplete) {
        // Not applicable to Isim
    }

    @Override
    public void setVoiceMessageWaiting(int line, int countWaiting) {
        // Not applicable to Isim
    }

    @UnsupportedAppUsage
    @Override
    protected void log(String s) {
        if (DBG) Rlog.d(LOG_TAG, "[ISIM] " + s);
    }

    @Override
    protected void loge(String s) {
        if (DBG) Rlog.e(LOG_TAG, "[ISIM] " + s);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("IsimRecords: " + this);
        pw.println(" extends:");
        super.dump(fd, pw, args);
        if (DUMP_RECORDS) {
            pw.println(" mIsimImpi=" + mIsimImpi);
            pw.println(" mIsimDomain=" + mIsimDomain);
            pw.println(" mIsimImpu[]=" + Arrays.toString(mIsimImpu));
            pw.println(" mIsimIst" + mIsimIst);
            pw.println(" mIsimPcscf" + mIsimPcscf);
        }
        pw.flush();
    }

    @Override
    public int getVoiceMessageCount() {
        return 0; // Not applicable to Isim
    }

}
