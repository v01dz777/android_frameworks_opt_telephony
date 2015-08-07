/*
 ** Copyright (c) 2015, The Linux Foundation. All rights reserved.

 ** Redistribution and use in source and binary forms, with or without
 ** modification, are permitted provided that the following conditions are
 ** met:
 **     * Redistributions of source code must retain the above copyright
 **       notice, this list of conditions and the following disclaimer.
 **     * Redistributions in binary form must reproduce the above
 **       copyright notice, this list of conditions and the following
 **       disclaimer in the documentation and/or other materials provided
 **       with the distribution.
 **     * Neither the name of The Linux Foundation nor the names of its
 **       contributors may be used to endorse or promote products derived
 **       from this software without specific prior written permission.

 ** THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 ** WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 ** MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 ** ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 ** BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 ** CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 ** SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 ** BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 ** WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 ** OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 ** IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.internal.telephony.uicc;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.os.Environment;
import android.telephony.Rlog;
import android.util.Xml;

import com.android.internal.util.XmlUtils;

public class PlmnOverride {
    private HashMap<String, String> mCarrierPlmnMap;


    static final String LOG_TAG = "PlmnOverride";
    static final String PARTNER_PLMN_OVERRIDE_PATH ="etc/plmn-conf.xml";

    public PlmnOverride () {
        mCarrierPlmnMap = new HashMap<String, String>();
        loadPlmnOverrides();
    }

    public boolean containsCarrier(String carrier) {
        return mCarrierPlmnMap.containsKey(carrier);
    }

    public String getPlmn(String carrier) {
        return mCarrierPlmnMap.get(carrier);
    }

    private void loadPlmnOverrides() {
        FileReader plmnReader;

        final File plmnFile = new File(Environment.getRootDirectory(),
                PARTNER_PLMN_OVERRIDE_PATH);

        try {
            plmnReader = new FileReader(plmnFile);
        } catch (FileNotFoundException e) {
            Rlog.w(LOG_TAG, "Can not open " +
                    Environment.getRootDirectory() + "/" + PARTNER_PLMN_OVERRIDE_PATH);
            return;
        }

        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(plmnReader);

            XmlUtils.beginDocument(parser, "plmnOverrides");

            while (true) {
                XmlUtils.nextElement(parser);

                String name = parser.getName();
                if (!"plmnOverride".equals(name)) {
                    break;
                }

                String numeric = parser.getAttributeValue(null, "numeric");
                String data    = parser.getAttributeValue(null, "plmn");

                mCarrierPlmnMap.put(numeric, data);
            }
            plmnReader.close();
        } catch (XmlPullParserException e) {
            Rlog.w(LOG_TAG, "Exception in plmn-conf parser " + e);
        } catch (IOException e) {
            Rlog.w(LOG_TAG, "Exception in plmn-conf parser " + e);
        }
    }

}
