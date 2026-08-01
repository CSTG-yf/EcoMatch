package com.tencent.supersonic.headless.server.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdkIntegrationTransportTest {

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> respond(exchange, 200, "ok".getBytes()));
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/ok");
            respond(exchange, 302, new byte[0]);
        });
        server.createContext("/large", exchange -> respond(exchange, 200, new byte[65]));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void doesNotFollowRedirectsAndRejectsOversizedResponses() {
        JdkIntegrationTransport transport = new JdkIntegrationTransport(Duration.ofSeconds(2), 64);

        IntegrationTransport.TransportResponse ok =
                transport.post(uri("/ok"), Map.of(), new byte[0], Duration.ofSeconds(2));
        assertEquals(200, ok.status());
        assertEquals(IntegrationErrorCode.UPSTREAM_REJECTED,
                assertThrows(IntegrationException.class, () -> transport.post(uri("/redirect"),
                        Map.of(), new byte[0], Duration.ofSeconds(2))).getCode());
        assertEquals(IntegrationErrorCode.RESPONSE_INVALID, assertThrows(IntegrationException.class,
                () -> transport.post(uri("/large"), Map.of(), new byte[0], Duration.ofSeconds(2)))
                        .getCode());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
