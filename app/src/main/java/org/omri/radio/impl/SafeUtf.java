package org.omri.radio.impl;

import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class SafeUtf {
    private static final String LOGTAG = "SafeUtf";

    public static String convertCStringToJniStringSafe(byte[] bArr) {
        if (bArr == null || bArr.length == 0) {
            return "";
        }
        ByteBuffer wrap = ByteBuffer.wrap(bArr);
        try {
            return String.valueOf(StandardCharsets.UTF_8.decode(wrap));
        } catch (Throwable unused) {
            Log.w(LOGTAG, Arrays.toString(bArr) + " not " + StandardCharsets.UTF_8);
            return "";
        }
    }
}
