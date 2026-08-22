package com.tencent.supersonic.common.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonUtilTest {

    @Test
    void localDateSerializesToIsoString() {
        String json = JsonUtil.toString(new DateHolder(LocalDate.of(2026, 3, 31)));
        assertTrue(json.contains("\"2026-03-31\""), "LocalDate 应序列化为 ISO 字符串: " + json);
    }

    @Test
    void legacyArrayDateStillDeserializes() {
        // 历史数据里 LocalDate 是 [2026,3,31] 数组格式，升级后必须仍可读回
        DateHolder holder = JsonUtil.toObject("{\"date\":[2026,3,31]}", DateHolder.class);
        assertEquals(LocalDate.of(2026, 3, 31), holder.getDate());
    }

    @Test
    void mapRoundTripKeepsIsoDate() {
        Map<String, Object> map =
                JsonUtil.toMap("{\"date\":\"2026-03-31\"}", String.class, Object.class);
        assertEquals("2026-03-31", map.get("date"));
    }

    static class DateHolder {

        private LocalDate date;

        public DateHolder() {}

        DateHolder(LocalDate date) {
            this.date = date;
        }

        public LocalDate getDate() {
            return date;
        }

        public void setDate(LocalDate date) {
            this.date = date;
        }
    }
}
