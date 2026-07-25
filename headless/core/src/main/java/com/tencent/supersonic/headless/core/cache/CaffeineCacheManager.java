package com.tencent.supersonic.headless.core.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.base.Joiner;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class CaffeineCacheManager implements CacheManager {

    @Autowired
    private CacheCommonConfig cacheCommonConfig;

    @Autowired
    @Qualifier("caffeineCache")
    private Cache<String, Object> caffeineCache;

    @Autowired
    @Qualifier("hotMetricCaffeineCache")
    private Cache<String, Object> hotMetricCaffeineCache;

    @Override
    public Boolean put(String key, Object value) {
        log.debug("[put caffeineCache] key:{}", key);
        caffeineCache.put(key, value);
        return true;
    }

    @Override
    public Object get(String key) {
        Object value = caffeineCache.asMap().get(key);
        log.debug("[get caffeineCache] key:{}, hit:{}", key, value != null);
        return value;
    }

    @Override
    public Boolean putHotMetric(String key, Object value) {
        log.debug("[put hotMetricCaffeineCache] key:{}", key);
        hotMetricCaffeineCache.put(key, value);
        return true;
    }

    @Override
    public Object getHotMetric(String key) {
        Object value = hotMetricCaffeineCache.asMap().get(key);
        log.debug("[get hotMetricCaffeineCache] key:{}, hit:{}", key, value != null);
        return value;
    }

    @Override
    public String generateCacheKey(String prefix, String body) {
        if (StringUtils.isEmpty(prefix)) {
            prefix = "-1";
        }
        return Joiner.on(":").join(cacheCommonConfig.getCacheCommonApp(),
                cacheCommonConfig.getCacheCommonEnv(), cacheCommonConfig.getCacheCommonVersion(),
                prefix, body);
    }

    @Override
    public Boolean removeCache(String key) {
        caffeineCache.asMap().remove(key);
        return true;
    }

    @Override
    public Boolean removeHotMetricCache(String key) {
        hotMetricCaffeineCache.invalidate(key);
        return true;
    }
}
