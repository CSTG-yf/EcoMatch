package com.tencent.supersonic.common.interceptor;



import com.tencent.supersonic.common.util.TraceIdUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

@Component
public class LogInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
            Object handler) {
        // Preserve a safe caller trace ID; replace malformed values to protect MDC and log output.
        String traceId = TraceIdUtil.resolveTraceId(request.getHeader(TraceIdUtil.TRACE_ID));
        TraceIdUtil.setTraceId(traceId);
        response.setHeader(TraceIdUtil.TRACE_ID, traceId);
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
            ModelAndView modelAndView) throws Exception {}

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception ex) throws Exception {
        // remove after Completing
        TraceIdUtil.remove();
    }
}
