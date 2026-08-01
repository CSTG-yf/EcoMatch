package com.tencent.supersonic.headless.server.integration;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public class JdkIntegrationTransport implements IntegrationTransport {

    private final HttpClient client;
    private final int maximumResponseBytes;

    public JdkIntegrationTransport(Duration connectTimeout, int maximumResponseBytes) {
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || maximumResponseBytes <= 0 || maximumResponseBytes > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("integration HTTP transport limits are invalid");
        }
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.maximumResponseBytes = maximumResponseBytes;
    }

    @Override
    public TransportResponse post(URI endpoint, Map<String, String> headers, byte[] body,
            Duration timeout) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        try {
            HttpResponse<InputStream> response =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                byte[] responseBody = stream.readNBytes(maximumResponseBytes + 1);
                if (responseBody.length > maximumResponseBytes) {
                    throw new IntegrationException(IntegrationErrorCode.RESPONSE_INVALID,
                            "integration response exceeded the configured size limit", false);
                }
                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                    throw new IntegrationException(IntegrationErrorCode.UPSTREAM_REJECTED,
                            "integration redirects are not allowed", false);
                }
                return new TransportResponse(response.statusCode(), responseBody);
            }
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new IntegrationException(IntegrationErrorCode.UPSTREAM_TIMEOUT,
                    "integration request timed out", true, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IntegrationException(IntegrationErrorCode.UPSTREAM_TIMEOUT,
                    "integration request was interrupted", true, exception);
        } catch (IOException exception) {
            throw new IntegrationException(IntegrationErrorCode.UPSTREAM_REJECTED,
                    "integration transport failed", true, exception);
        }
    }
}
