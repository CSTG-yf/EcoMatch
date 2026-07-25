package com.tencent.supersonic.common.interceptor;

import com.tencent.supersonic.common.util.TraceIdUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogInterceptorTest {

    private final LogInterceptor interceptor = new LogInterceptor();

    @AfterEach
    void clearMdc() {
        TraceIdUtil.clear();
    }

    @Test
    void preservesValidInboundTraceIdAndReturnsItToTheCaller() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(TraceIdUtil.TRACE_ID, "client-trace_123.abc:1");

        assertTrue(interceptor.preHandle(request, response, new Object()));

        assertEquals("client-trace_123.abc:1", TraceIdUtil.getTraceId());
        assertEquals("client-trace_123.abc:1", response.getHeader(TraceIdUtil.TRACE_ID));
    }

    @Test
    void replacesInvalidOrOversizedInboundTraceId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String invalidTraceId = "x".repeat(TraceIdUtil.MAX_TRACE_ID_LENGTH + 1);
        request.addHeader(TraceIdUtil.TRACE_ID, invalidTraceId);

        assertTrue(interceptor.preHandle(request, response, new Object()));

        String resolvedTraceId = TraceIdUtil.getTraceId();
        assertNotEquals(invalidTraceId, resolvedTraceId);
        assertTrue(TraceIdUtil.isValidTraceId(resolvedTraceId));
        assertEquals(resolvedTraceId, response.getHeader(TraceIdUtil.TRACE_ID));
        assertFalse(TraceIdUtil.isValidTraceId("trace id with spaces"));
    }

    @Test
    void removesTraceIdAfterRequestCompletion() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.preHandle(request, response, new Object());

        interceptor.afterCompletion(request, response, new Object(), null);

        assertEquals("", TraceIdUtil.getTraceId());
    }
}
