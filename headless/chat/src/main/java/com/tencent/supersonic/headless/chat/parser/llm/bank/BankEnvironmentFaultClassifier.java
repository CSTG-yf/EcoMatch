package com.tencent.supersonic.headless.chat.parser.llm.bank;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Detects provider/infrastructure outages (expired API key, rate limit, transport timeout,
 * gateway errors) that no {@code BankQueryPlan} regeneration can ever fix. Every caller must
 * treat a hit as a fast terminal state: skip the remaining model rounds and surface the cause,
 * mirroring the live cloud key-death incident of 2026-08-27.
 */
public final class BankEnvironmentFaultClassifier {

    /** Non-repairable marker stored as {@code errorCode}; deliberately outside repair whitelists. */
    public static final String CODE = "ENVIRONMENT_FAULT";

    private static final String[] MARKERS = {
            "invalid_api_key", "invalid api key", "incorrect api key", "api key", "apikey",
            "autherror", "authentication", "authenticated user", "unauthorized",
            "permission denied", "access denied", "insufficient_quota", "quota exceeded",
            "billing", "rate limit", "ratelimit", "too many requests", "request limit",
            "timed out", "timeout", "connection refused", "connection reset", "connection closed",
            "connectException", "socketTimeout", "unknownhost", "unresolvedaddress",
            "unreachable", "sslhandshake", "broken pipe", "stream closed",
            "service unavailable", "bad gateway", "overloaded"
    };

    /** Only treats digits as an HTTP status when they follow an explicit http/status cue. */
    private static final Pattern HTTP_STATUS = Pattern.compile(
            "(?i)(?:http|status|code)[^0-9]{0,6}(401|403|429|500|502|503|504)");

    private BankEnvironmentFaultClassifier() {}

    /** True when either the machine code or the human-readable failure carries an outage marker. */
    public static boolean isEnvironmentFault(String errorCode, String message) {
        return CODE.equals(errorCode) || matches(errorCode) || matches(message);
    }

    /** Walks the whole cause chain looking for outage signatures in types and messages. */
    public static boolean isEnvironmentFault(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 16) {
            if (matches(current.getClass().getSimpleName()) || matches(current.getMessage())) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    private static boolean matches(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String marker : MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return HTTP_STATUS.matcher(normalized).find();
    }
}
