package com.tencent.supersonic.common.util;

import org.apache.commons.codec.digest.DigestUtils;

/** Produces correlation-safe log metadata without exposing request or SQL contents. */
public final class SensitiveLogUtils {

    private SensitiveLogUtils() {}

    public static String summarize(Object value) {
        if (value == null) {
            return "null";
        }
        String text = String.valueOf(value);
        return "sha256=" + DigestUtils.sha256Hex(text) + ",chars=" + text.length();
    }
}
