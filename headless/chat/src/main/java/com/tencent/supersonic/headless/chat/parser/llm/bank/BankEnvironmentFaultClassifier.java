package com.tencent.supersonic.headless.chat.parser.llm.bank;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects provider/infrastructure outages (expired API key, rate limit, transport timeout,
 * gateway errors) that no {@code BankQueryPlan} regeneration can ever fix. Every caller must
 * treat a hit as a fast terminal state: skip the remaining model rounds and surface the cause,
 * mirroring the live cloud key-death incident of 2026-08-27.
 *
 * <p>
 * Within that terminal bucket, {@link #isTransientTransportFault} isolates the pure
 * transport-blip subset (timeout, connection reset, gateway 502/503/504, ...) that one
 * immediate re-roll of the same request can plausibly heal; hard provider faults (auth,
 * quota, rate limit, 500) never qualify and stay terminal on the first roll.
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

    /**
     * Transport-only subset of {@link #MARKERS}: endpoint blips where the request never got a
     * real model answer, so an immediate re-roll can self-heal. Deliberately excludes every hard
     * provider marker (api key / auth / quota / rate limit) — those must stay terminal.
     * Lowercase entries so they match the lowercased text and exception simple names.
     */
    private static final String[] TRANSIENT_TRANSPORT_MARKERS = {
            "timed out", "timeout", "connection refused", "connection reset", "connection closed",
            "connectexception", "sockettimeout", "socket", "unknownhost", "unresolvedaddress",
            "unreachable", "sslhandshake", "broken pipe", "stream closed",
            "service unavailable", "bad gateway", "overloaded"
    };

    /**
     * Only 502/503/504 are transient gateway conditions; the ambiguous 500 and the
     * auth/quota-related 401/403/429 stay terminal.
     */
    private static final Pattern TRANSIENT_HTTP_STATUS = Pattern.compile(
            "(?i)(?:http|status|code)[^0-9]{0,6}(502|503|504)");

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

    /** True when either failure text carries a transient transport marker (and only then). */
    public static boolean isTransientTransportFault(String errorCode, String message) {
        return transientTransportCategory(errorCode) != null
                || transientTransportCategory(message) != null;
    }

    /** Walks the whole cause chain looking for transient transport signatures. */
    public static boolean isTransientTransportFault(Throwable error) {
        return transientTransportCategory(error) != null;
    }

    /**
     * Human-readable hit reason (the matched marker or the matched gateway status) for logs;
     * null when the text carries no transient transport signature.
     */
    public static String transientTransportCategory(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 16) {
            String category = transientTransportCategory(current.getClass().getSimpleName());
            if (category == null) {
                category = transientTransportCategory(current.getMessage());
            }
            if (category != null) {
                return category;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return null;
    }

    static String transientTransportCategory(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String marker : TRANSIENT_TRANSPORT_MARKERS) {
            if (normalized.contains(marker)) {
                return marker;
            }
        }
        Matcher matcher = TRANSIENT_HTTP_STATUS.matcher(normalized);
        if (matcher.find()) {
            return "http_" + matcher.group(1);
        }
        return null;
    }
}
