package com.tencent.supersonic.common.util;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

public class TraceIdUtil {
    public static final String TRACE_ID = "traceId";

    public static final String PREFIX = "supersonic";

    public static final int MAX_TRACE_ID_LENGTH = 128;

    private static final Pattern VALID_TRACE_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0," + (MAX_TRACE_ID_LENGTH - 1) + "}");

    public static String getTraceId() {
        String traceId = (String) MDC.get(TRACE_ID);
        return traceId == null ? "" : traceId;
    }

    public static void setTraceId(String traceId) {
        MDC.put(TRACE_ID, traceId);
    }

    public static void remove() {
        MDC.remove(TRACE_ID);
    }

    public static void clear() {
        MDC.clear();
    }

    public static boolean isValidTraceId(String traceId) {
        return traceId != null && VALID_TRACE_ID.matcher(traceId).matches();
    }

    public static String resolveTraceId(String candidate) {
        return isValidTraceId(candidate) ? candidate : generateTraceId();
    }

    public static String generateTraceId() {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return PREFIX + "_" + uuid;
    }
}
