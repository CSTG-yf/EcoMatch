package com.tencent.supersonic.headless.core.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CaffeineCacheManagerTest {

    @Test
    void keepsHotMetricEntriesInDedicatedCache() throws Exception {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        setField(manager, "caffeineCache", Caffeine.newBuilder().maximumSize(10).build());
        setField(manager, "hotMetricCaffeineCache", Caffeine.newBuilder().maximumSize(10).build());

        manager.put("normal", "normal-result");
        manager.putHotMetric("hot", "hot-result");

        assertEquals("normal-result", manager.get("normal"));
        assertNull(manager.getHotMetric("normal"));
        assertEquals("hot-result", manager.getHotMetric("hot"));
        assertNull(manager.get("hot"));
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
