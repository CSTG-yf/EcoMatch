package com.tencent.supersonic.headless.server.integration;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@ConditionalOnProperty(prefix = "s2.integration", name = "enabled", havingValue = "true")
@RequestMapping("/api/semantic/integration/v1")
public class IntegrationCallbackController {

    private final InboundIntegrationService inboundService;

    public IntegrationCallbackController(InboundIntegrationService inboundService) {
        this.inboundService = inboundService;
    }

    @PostMapping(value = "/callbacks/{systemId}/{operation}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IntegrationResponse> callback(@PathVariable String systemId,
            @PathVariable String operation, @RequestBody byte[] body, HttpServletRequest request) {
        String traceId = request.getHeader(HmacIntegrationSigner.HEADER_TRACE);
        try {
            IntegrationResponse response = inboundService.receive(systemId, operation,
                    request.getRequestURI(), authenticationHeaders(request), body);
            return ResponseEntity.ok(response);
        } catch (IntegrationException failure) {
            IntegrationResponse response = new IntegrationResponse("v1", failure.getCode(),
                    safeMessage(failure.getCode()), traceId, failure.isRetryable(), Map.of());
            return ResponseEntity.status(status(failure.getCode())).body(response);
        } catch (RuntimeException failure) {
            IntegrationResponse response =
                    new IntegrationResponse("v1", IntegrationErrorCode.INTERNAL_ERROR,
                            "integration failed", traceId, true, Map.of());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    private Map<String, String> authenticationHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        copy(request, headers, HmacIntegrationSigner.HEADER_SYSTEM);
        copy(request, headers, HmacIntegrationSigner.HEADER_TIMESTAMP);
        copy(request, headers, HmacIntegrationSigner.HEADER_NONCE);
        copy(request, headers, HmacIntegrationSigner.HEADER_IDEMPOTENCY);
        copy(request, headers, HmacIntegrationSigner.HEADER_TRACE);
        copy(request, headers, HmacIntegrationSigner.HEADER_SIGNATURE);
        return Map.copyOf(headers);
    }

    private void copy(HttpServletRequest request, Map<String, String> headers, String name) {
        String value = request.getHeader(name);
        if (value != null) {
            headers.put(name, value);
        }
    }

    private HttpStatus status(IntegrationErrorCode code) {
        return switch (code) {
            case AUTHENTICATION_FAILED, REPLAY_DETECTED -> HttpStatus.UNAUTHORIZED;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
            case UNSUPPORTED_OPERATION -> HttpStatus.NOT_FOUND;
            case UPSTREAM_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case UPSTREAM_REJECTED, RESPONSE_INVALID -> HttpStatus.BAD_GATEWAY;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private String safeMessage(IntegrationErrorCode code) {
        return switch (code) {
            case AUTHENTICATION_FAILED, REPLAY_DETECTED -> "authentication rejected";
            case RATE_LIMITED -> "rate limit exceeded";
            case IDEMPOTENCY_CONFLICT -> "idempotency conflict";
            case UNSUPPORTED_OPERATION -> "operation not found";
            case UPSTREAM_TIMEOUT -> "operation timed out";
            case UPSTREAM_REJECTED, RESPONSE_INVALID -> "upstream integration failed";
            case INTERNAL_ERROR -> "integration failed";
            default -> "request rejected";
        };
    }
}
