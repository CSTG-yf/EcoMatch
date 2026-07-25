package com.tencent.supersonic.common.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ThreadMdcUtilTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void supplierCarriesParentTraceIntoAsyncWork() {
        MDC.put(TraceIdUtil.TRACE_ID, "trace-parent");

        String childTrace = CompletableFuture.supplyAsync(
                ThreadMdcUtil.wrapSupplier(TraceIdUtil::getTraceId, MDC.getCopyOfContextMap()))
                .join();

        assertEquals("trace-parent", childTrace);
        assertEquals("trace-parent", TraceIdUtil.getTraceId());
    }

    @Test
    void supplierCreatesTraceWhenParentHasNoContext() {
        String childTrace = CompletableFuture
                .supplyAsync(ThreadMdcUtil.wrapSupplier(TraceIdUtil::getTraceId, null)).join();

        assertFalse(childTrace.isBlank());
    }
}
