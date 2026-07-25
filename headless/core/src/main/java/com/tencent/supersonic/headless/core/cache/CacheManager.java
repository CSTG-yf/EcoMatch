package com.tencent.supersonic.headless.core.cache;

public interface CacheManager {

    Boolean put(String key, Object value);

    Object get(String key);

    default Boolean putHotMetric(String key, Object value) {
        return put(key, value);
    }

    default Object getHotMetric(String key) {
        return get(key);
    }

    String generateCacheKey(String prefix, String body);

    Boolean removeCache(String key);

    default Boolean removeHotMetricCache(String key) {
        return removeCache(key);
    }
}
