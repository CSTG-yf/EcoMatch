package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaCppPrefixChatClientTest {

    @AfterEach
    void clearJsonSchemaCapabilities() {
        LlamaCppPrefixChatClient.clearJsonSchemaCapabilitiesForTests();
    }

    @Test
    void stripThinkingRemovesThinkBlocks() {
        String raw = "<think>\n先分析机构再写SQL\n</think>\n{\"thought\":\"ok\",\"sql\":\"SELECT 1\"}";
        String stripped = LlamaCppPrefixChatClient.stripThinking(raw);
        assertFalse(stripped.contains("先分析"));
        assertTrue(stripped.contains("SELECT 1"));
    }

    @Test
    void stripThinkingKeepsPlainJson() {
        String raw = "{\"thought\":\"t\",\"sql\":\"SELECT 2\"}";
        assertEquals(raw, LlamaCppPrefixChatClient.stripThinking(raw));
    }

    @Test
    void warmupUsesMinimalCompletionWithoutChangingThinkingMode() {
        LlamaCppPrefixChatClient.ChatOptions nonThinking =
                LlamaCppPrefixChatClient.ChatOptions.warmup(false);
        assertFalse(nonThinking.enableThinking());
        assertEquals(1, nonThinking.maxTokens());
        assertTrue(nonThinking.omitResponseFormat());

        LlamaCppPrefixChatClient.ChatOptions thinking =
                LlamaCppPrefixChatClient.ChatOptions.warmup(true);
        assertTrue(thinking.enableThinking());
        assertEquals(1024, thinking.maxTokens());
        assertTrue(thinking.omitResponseFormat());
    }

    @Test
    void nonThinkingRequestsExplicitlyDisableQwenReasoningAndKeepJsonFormat() {
        ObjectNode body = new ObjectMapper().createObjectNode();
        ChatModelConfig config = new ChatModelConfig();
        config.setJsonFormat(true);
        config.setJsonFormatType("json_object");

        LlamaCppPrefixChatClient.applyThinkingOptions(body, config,
                LlamaCppPrefixChatClient.ChatOptions.defaults());

        assertFalse(body.get("enable_thinking").asBoolean());
        assertFalse(body.path("chat_template_kwargs").get("enable_thinking").asBoolean());
        assertEquals("json_object", body.path("response_format").path("type").asText());
    }

    @Test
    void jsonSchemaRequestsCarryTheNamedStrictSchemaInsteadOfBeingDowngraded() throws Exception {
        ObjectNode body = new ObjectMapper().createObjectNode();
        ChatModelConfig config = jsonSchemaConfig();

        LlamaCppPrefixChatClient.applyThinkingOptions(body, config,
                LlamaCppPrefixChatClient.ChatOptions.jsonSchema("bank_request_contract",
                        BankRequestContract.JSON_SCHEMA));

        assertEquals("json_schema", body.path("response_format").path("type").asText());
        assertEquals("bank_request_contract", body.path("response_format").path("json_schema")
                .path("name").asText());
        assertFalse(body.path("response_format").path("json_schema").path("schema")
                .path("additionalProperties").asBoolean(true));
    }

    @Test
    void publicOpenAiEndpointOmitsLlamaCppOnlyRequestFields() {
        ChatModelConfig config = jsonSchemaConfig();
        config.setBaseUrl("https://www.autodl.art/api/v1");
        config.setModelName("gpt-5.6-luna");

        ObjectNode body = new LlamaCppPrefixChatClient().createRequestBody(config,
                "stable system", "real user request",
                LlamaCppPrefixChatClient.ChatOptions.jsonSchema("bank_request_contract",
                        BankRequestContract.JSON_SCHEMA),
                true);

        assertFalse(body.has("cache_prompt"));
        assertFalse(body.has("enable_thinking"));
        assertFalse(body.has("chat_template_kwargs"));
        assertEquals("json_schema", body.path("response_format").path("type").asText());
    }

    @Test
    void reasoningEffortTravelsOnOpenAiWireAndStaysOffLlamaCppPayloads() {
        ChatModelConfig cloud = plainChatConfig();
        cloud.setBaseUrl("https://www.autodl.art/api/v1");
        cloud.setReasoningEffort(" low ");

        ObjectNode cloudBody = new LlamaCppPrefixChatClient().createRequestBody(cloud,
                "stable system", "real user request",
                LlamaCppPrefixChatClient.ChatOptions.defaults(), true);
        assertEquals("low", cloudBody.path("reasoning_effort").asText());

        ChatModelConfig local = plainChatConfig();
        local.setBaseUrl("http://127.0.0.1:8899");
        local.setReasoningEffort("low");

        ObjectNode localBody = new LlamaCppPrefixChatClient().createRequestBody(local,
                "stable system", "real user request",
                LlamaCppPrefixChatClient.ChatOptions.defaults(), true);
        assertFalse(localBody.has("reasoning_effort"),
                "llama.cpp payloads keep their native thinking switches");

        ChatModelConfig unset = plainChatConfig();
        unset.setBaseUrl("https://api.openai.com/v1");

        assertFalse(new LlamaCppPrefixChatClient().createRequestBody(unset,
                "stable system", "real user request",
                LlamaCppPrefixChatClient.ChatOptions.defaults(), true).has("reasoning_effort"));
    }

    @Test
    void publicOpenAiEndpointUsesStrictSchemaWithEveryPropertyRequired() {
        ChatModelConfig config = jsonSchemaConfig();
        config.setBaseUrl("https://www.autodl.art/api/v1");
        config.setModelName("gpt-5.6-luna");

        ObjectNode body = new LlamaCppPrefixChatClient().createRequestBody(config,
                "stable system", "real user request",
                LlamaCppPrefixChatClient.ChatOptions.jsonSchema("bank_planning_response",
                        BankPlanningResponse.JSON_SCHEMA), true);

        assertTrue(body.path("response_format").path("json_schema").path("strict").asBoolean());
        var schema = body.path("response_format").path("json_schema").path("schema");
        assertStrictObjectPropertiesRequired(schema);
        assertEquals("string", schema.path("properties").path("plan").path("properties")
                .path("version").path("type").asText());
        assertFalse(schema.path("properties").path("plan").path("properties").path("version")
                .has("const"));
    }

    private static void assertStrictObjectPropertiesRequired(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            var properties = node.path("properties");
            if (properties.isObject()) {
                var required = new java.util.HashSet<String>();
                node.path("required").forEach(value -> required.add(value.asText()));
                properties.fieldNames().forEachRemaining(name ->
                        assertTrue(required.contains(name), "missing required property: " + name));
            }
            node.fields().forEachRemaining(entry -> assertStrictObjectPropertiesRequired(entry.getValue()));
        } else if (node.isArray()) {
            node.forEach(LlamaCppPrefixChatClientTest::assertStrictObjectPropertiesRequired);
        }
    }

    @Test
    void supportedServerReceivesOneSchemaProbeThenTheActualSchemaBoundRequest() throws Exception {
        try (TestServer server = new TestServer(List.of(200, 200))) {
            LlamaCppPrefixChatClient client = new LlamaCppPrefixChatClient();
            LlamaCppPrefixChatClient.ChatResult answer = client.chat(server.config(), "stable system", "real user request",
                    LlamaCppPrefixChatClient.ChatOptions.jsonSchema("bank_request_contract",
                            BankRequestContract.JSON_SCHEMA));

            assertEquals("{\"ok\":true}", answer.content());
            assertEquals(2, server.requests.size());
            assertEquals("json_schema", server.requests.get(0).path("response_format")
                    .path("type").asText());
            assertEquals("json_schema", server.requests.get(1).path("response_format")
                    .path("type").asText());
            assertFalse(server.requests.get(0).path("messages").get(1).path("content").asText()
                    .contains("real user request"));
            assertEquals("real user request", server.requests.get(1).path("messages").get(1)
                    .path("content").asText());
        }
    }

    @Test
    void providerSchemaKeepsContractShapeButOmitsUnsupportedValidationKeywords() throws Exception {
        try (TestServer server = new TestServer(List.of(200, 200))) {
            LlamaCppPrefixChatClient client = new LlamaCppPrefixChatClient();
            client.chat(server.config(), "stable system", "real user request",
                    LlamaCppPrefixChatClient.ChatOptions.jsonSchema("bank_request_contract",
                            BankRequestContract.JSON_SCHEMA));

            var schema = server.requests.get(1).path("response_format").path("json_schema")
                    .path("schema");
            assertTrue(schema.path("required").toString().contains("metricCodes"));
            assertTrue(schema.path("properties").path("metricCodes").path("items")
                    .has("enum"));
            assertTrue(schema.path("properties").path("time").path("type").isArray(),
                    "nullable fields must retain their provider-supported type union");
            assertFalse(schema.findValue("format") != null,
                    "llama.cpp 66 rejects format when additionalProperties is false");
            assertFalse(schema.findValue("pattern") != null,
                    "llama.cpp 66 rejects pattern when additionalProperties is false");
            assertFalse(schema.findValue("minimum") != null,
                    "provider schema must leave numeric semantics to the local validator");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 501})
    void unsupportedSchemaStatusFallsBackToJsonObjectAndCachesThatDecision(int unsupportedStatus)
            throws Exception {
        try (TestServer server = new TestServer(List.of(unsupportedStatus, 200, 200))) {
            LlamaCppPrefixChatClient client = new LlamaCppPrefixChatClient();
            LlamaCppPrefixChatClient.ChatOptions options =
                    LlamaCppPrefixChatClient.ChatOptions.jsonSchema("bank_query_plan",
                            BankQueryPlan.JSON_SCHEMA);

            assertEquals("{\"ok\":true}", client.chat(server.config(), "stable system",
                    "first user request", options).content());
            assertEquals("{\"ok\":true}", client.chat(server.config(), "stable system",
                    "second user request", options).content());

            assertEquals(3, server.requests.size());
            assertEquals("json_schema", server.requests.get(0).path("response_format")
                    .path("type").asText());
            assertEquals("json_object", server.requests.get(1).path("response_format")
                    .path("type").asText());
            assertEquals("json_object", server.requests.get(2).path("response_format")
                    .path("type").asText());
        }
    }

    @Test
    void unexpectedSchemaProbeStatusFailsClosedWithoutLoggingTheResponseBody() throws Exception {
        try (TestServer server = new TestServer(List.of(500))) {
            LlamaCppPrefixChatClient.JsonSchemaCapabilityException exception = assertThrows(
                    LlamaCppPrefixChatClient.JsonSchemaCapabilityException.class,
                    () -> new LlamaCppPrefixChatClient().chat(server.config(), "system", "user",
                            LlamaCppPrefixChatClient.ChatOptions.jsonSchema("bank_query_plan",
                                    BankQueryPlan.JSON_SCHEMA)));

            assertTrue(exception.getMessage().contains("unexpected_status_500"));
            assertFalse(exception.getMessage().contains("json schema unsupported"));
        }
    }

    @Test
    void thinkingRequestsEnableReasoningAndDoNotForceJsonFormat() {
        ObjectNode body = new ObjectMapper().createObjectNode();
        ChatModelConfig config = new ChatModelConfig();
        config.setJsonFormat(true);

        LlamaCppPrefixChatClient.applyThinkingOptions(body, config,
                LlamaCppPrefixChatClient.ChatOptions.thinking(1024));

        assertTrue(body.get("enable_thinking").asBoolean());
        assertTrue(body.path("chat_template_kwargs").get("enable_thinking").asBoolean());
        assertTrue(body.get("response_format") == null);
    }

    @Test
    void parseResponseExtractsOnlyWhitelistedTimingsAndUsage() throws Exception {
        String body = """
                {"choices":[{"message":{"content":"{\\"plan\\":1}",
                "reasoning_content":"短推理"}}],
                "timings":{"prompt_n":21,"cache_n":18,"prompt_ms":120.5,"predicted_ms":330.2,
                "prompt_per_second":40.1,"predicted_per_second":12.3,
                "unexpected_text_key":"must not leak through"},
                "usage":{"prompt_tokens":21,"completion_tokens":7}}
                """;

        LlamaCppPrefixChatClient.ChatResult result =
                new LlamaCppPrefixChatClient().parseResponse(body, true);

        assertEquals("{\"plan\":1}", result.content());
        assertTrue(result.thinkingEnabled());
        assertTrue(result.reasoningChars() > 0);
        assertTrue(result.timings().containsKey("prompt_n"));
        assertTrue(result.timings().containsKey("cache_n"));
        assertTrue(result.timings().containsKey("prompt_ms"));
        assertTrue(result.timings().containsKey("predicted_ms"));
        assertTrue(result.timings().containsKey("prompt_tokens"));
        assertTrue(result.timings().containsKey("completion_tokens"));
        assertFalse(result.timings().containsKey("unexpected_text_key"),
                "timings must be restricted to a numeric whitelist");
    }

    @Test
    void parseResponseNeverRetainsPromptOrResultText() throws Exception {
        String body = """
                {"choices":[{"message":{"content":"{\\"sql\\":\\"SELECT * FROM t\\"}"}}],
                "timings":{"prompt_n":5,"predicted_n":3}}
                """;

        LlamaCppPrefixChatClient.ChatResult result =
                new LlamaCppPrefixChatClient().parseResponse(body, false);

        assertFalse(result.timings().keySet().stream()
                .anyMatch(key -> key.toLowerCase().contains("content")
                        || key.toLowerCase().contains("prompt_text")
                        || key.toLowerCase().contains("result")),
                "timings must never carry prompt or result text");
        assertFalse(result.timings().values().stream()
                .anyMatch(value -> value instanceof String s && s.contains("SELECT")),
                "timings must never carry SQL or prompt text");
    }

    @Test
    void safetyCappedOptionsBoundTheDecodeWithoutTouchingThinking() {
        LlamaCppPrefixChatClient.ChatOptions capped =
                LlamaCppPrefixChatClient.ChatOptions.safetyCap(1024);

        assertEquals(1024, capped.maxTokens());
        assertFalse(capped.enableThinking());
    }

    private static ChatModelConfig jsonSchemaConfig() {
        ChatModelConfig config = new ChatModelConfig();
        config.setJsonFormat(true);
        config.setJsonFormatType("json_schema");
        return config;
    }

    private static ChatModelConfig plainChatConfig() {
        ChatModelConfig config = new ChatModelConfig();
        config.setProvider("OPEN_AI");
        config.setApiKey("sk-test");
        config.setTemperature(0.0d);
        config.setJsonFormat(false);
        return config;
    }

    private static final class TestServer implements AutoCloseable {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final List<Integer> statuses;
        private final HttpServer server;
        private final List<com.fasterxml.jackson.databind.JsonNode> requests = new ArrayList<>();

        private TestServer(List<Integer> statuses) throws IOException {
            this.statuses = statuses;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                try (exchange) {
                    requests.add(MAPPER.readTree(exchange.getRequestBody().readAllBytes()));
                    int index = requests.size() - 1;
                    int status = statuses.get(Math.min(index, statuses.size() - 1));
                    byte[] response = (status >= 200 && status < 300
                            ? "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}"
                            : "{\"error\":\"json schema unsupported\"}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, response.length);
                    exchange.getResponseBody().write(response);
                }
            });
            server.start();
        }

        private ChatModelConfig config() {
            ChatModelConfig config = jsonSchemaConfig();
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            config.setModelName("test-bank-model");
            config.setTimeOut(5L);
            return config;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
