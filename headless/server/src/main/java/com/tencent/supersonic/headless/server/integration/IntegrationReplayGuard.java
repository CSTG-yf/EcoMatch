package com.tencent.supersonic.headless.server.integration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;

public class IntegrationReplayGuard {

    private final Cache<String, Boolean> nonces;

    public IntegrationReplayGuard(long maximumEntries, Duration ttl) {
        if (maximumEntries <= 0 || ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("integration replay guard limits are invalid");
        }
        nonces = Caffeine.newBuilder().maximumSize(maximumEntries).expireAfterWrite(ttl).build();
    }

    public void requireFresh(String systemId, String nonce) {
        String key = systemId + ':' + nonce;
        if (nonces.asMap().putIfAbsent(key, Boolean.TRUE) != null) {
            throw new IntegrationException(IntegrationErrorCode.REPLAY_DETECTED,
                    "integration request nonce was already used", false);
        }
    }
}
