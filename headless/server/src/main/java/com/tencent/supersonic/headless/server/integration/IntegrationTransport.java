package com.tencent.supersonic.headless.server.integration;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

public interface IntegrationTransport {

    TransportResponse post(URI endpoint, Map<String, String> headers, byte[] body,
            Duration timeout);

    record TransportResponse(int status, byte[] body) {
    }
}
