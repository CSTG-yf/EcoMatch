package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Direct OpenAI-compatible chat client with optional llama.cpp prompt/prefix caching.
 *
 * <p>
 * llama-server reuses KV tokens for the longest common token prefix when {@code cache_prompt=true}.
 * We always send a stable system message first, then only the dynamic user payload, so the system
 * prefix can stay resident across requests after warm-up.
 *
 * <p>
 * Optional deep-thinking mode sets {@code chat_template_kwargs.enable_thinking} (Qwen3 / compatible
 * llama.cpp builds) and strips {@code <think>} / {@code reasoning_content} before returning the
 * final answer text.
 */
public class LlamaCppPrefixChatClient {

    private static final Logger LOG = LoggerFactory.getLogger(LlamaCppPrefixChatClient.class);
    private static final Logger KEY_PIPELINE = LoggerFactory.getLogger("keyPipeline");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ConcurrentMap<JsonSchemaCapabilityKey, JsonSchemaCapability>
            JSON_SCHEMA_CAPABILITIES = new ConcurrentHashMap<>();
    private static final String JSON_SCHEMA_PROBE_NAME = "bank_json_schema_capability_probe";
    private static final String JSON_SCHEMA_PROBE_SYSTEM =
            "You are a capability probe. Return only the requested JSON object.";
    private static final String JSON_SCHEMA_PROBE_USER =
            "Return a minimal capability acknowledgement.";
    private static final String JSON_SCHEMA_PROBE = """
            {"type":"object","additionalProperties":false,"required":["ok"],
            "properties":{"ok":{"type":"boolean"}}}
            """.strip();

    private static final Pattern THINK_BLOCK = Pattern.compile("(?is)<think\\b[^>]*>.*?</think>");
    private static final Pattern THINK_BLOCK_ALT =
            Pattern.compile("(?is)<thinking\\b[^>]*>.*?</thinking>");
    private static final Pattern REDACTED_THINKING =
            Pattern.compile("(?is)<\\|?redacted_thinking\\|?>.*?<\\/?redacted_thinking\\|?>");

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public record ChatOptions(boolean enableThinking, int maxTokens, String jsonSchemaName,
            String jsonSchema, boolean omitResponseFormat) {

        public ChatOptions(boolean enableThinking, int maxTokens) {
            this(enableThinking, maxTokens, null, null, false);
        }

        public static ChatOptions defaults() {
            return new ChatOptions(false, 0);
        }

        /**
         * Safety bound for the maximum decoded output length. It only guards against runaway
         * generation; the value must stay above the verified per-stage P99 output maxima.
         */
        public static ChatOptions safetyCap(int maxTokens) {
            return new ChatOptions(false, Math.max(0, maxTokens));
        }

        /**
         * Options for warming a fixed prompt prefix. The warm-up response is discarded, so it
         * should consume the smallest possible completion while still exercising the chat template.
         */
        public static ChatOptions warmup(boolean thinkingEnabled) {
            return new ChatOptions(thinkingEnabled, thinkingEnabled ? 1024 : 1, null, null, true);
        }

        public static ChatOptions thinking(int maxTokens) {
            return new ChatOptions(true, Math.max(1024, maxTokens));
        }

        public static ChatOptions jsonSchema(String name, String schema) {
            if (StringUtils.isBlank(name) || StringUtils.isBlank(schema)) {
                throw new IllegalArgumentException("json schema name and schema are required");
            }
            return new ChatOptions(false, 0, name, schema, false);
        }

        public ChatOptions withMaxTokens(int maximum) {
            return new ChatOptions(enableThinking, Math.max(0, maximum), jsonSchemaName,
                    jsonSchema, omitResponseFormat);
        }

        public ChatOptions withJsonSchema(String name, String schema) {
            if (StringUtils.isBlank(name) || StringUtils.isBlank(schema)) {
                throw new IllegalArgumentException("json schema name and schema are required");
            }
            return new ChatOptions(enableThinking, maxTokens, name, schema, omitResponseFormat);
        }

        public boolean hasJsonSchema() {
            return StringUtils.isNotBlank(jsonSchemaName) && StringUtils.isNotBlank(jsonSchema);
        }
    }

    public record ChatResult(String content, Map<String, Object> timings,
            boolean cachePromptEnabled, boolean thinkingEnabled, int reasoningChars) {}

    public ChatResult chat(ChatModelConfig config, String systemPrefix, String userContent) {
        return chat(config, systemPrefix, userContent, ChatOptions.defaults());
    }

    public ChatResult chat(ChatModelConfig config, String systemPrefix, String userContent,
            ChatOptions options) {
        Objects.requireNonNull(config, "chat model config");
        ChatOptions opts = options == null ? ChatOptions.defaults() : options;
        if (StringUtils.isBlank(config.getBaseUrl())) {
            throw new IllegalArgumentException("chat model baseUrl is required for llama.cpp");
        }
        if (StringUtils.isBlank(systemPrefix) || StringUtils.isBlank(userContent)) {
            throw new IllegalArgumentException("system prefix and user content are required");
        }

        String url = resolveChatCompletionsUrl(config.getBaseUrl());
        boolean llamaCppExtensions = usesLlamaCppExtensions(config.getBaseUrl());
        try {
            boolean schemaSupported = !isJsonSchemaRequest(config, opts)
                    || resolveJsonSchemaCapability(config, url) == JsonSchemaCapability.SUPPORTED;
            ObjectNode body = createRequestBody(config, systemPrefix, userContent, opts,
                    schemaSupported);
            HttpResponse<String> response = post(config, url, body, opts);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw failedResponse(response);
            }
            return parseResponse(response.body(), opts.enableThinking(), llamaCppExtensions);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("llama.cpp chat interrupted", e);
        } catch (Exception e) {
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("llama.cpp chat request failed", e);
        }
    }

    /**
     * Applies the model-native thinking switch before serializing the request. Qwen3.6 defaults to
     * emitting reasoning_content even when the caller asks for JSON; explicitly sending false is
     * required for the final JSON to be returned in message.content.
     */
    static void applyThinkingOptions(ObjectNode body, ChatModelConfig config, ChatOptions options) {
        applyThinkingOptions(body, config, options, true);
    }

    private static void applyThinkingOptions(ObjectNode body, ChatModelConfig config,
            ChatOptions options, boolean jsonSchemaSupported) {
        applyThinkingOptions(body, config, options, jsonSchemaSupported, true);
    }

    private static void applyThinkingOptions(ObjectNode body, ChatModelConfig config,
            ChatOptions options, boolean jsonSchemaSupported, boolean llamaCppExtensions) {
        ChatOptions opts = options == null ? ChatOptions.defaults() : options;
        if (llamaCppExtensions) {
            ObjectNode templateKwargs = body.putObject("chat_template_kwargs");
            templateKwargs.put("enable_thinking", opts.enableThinking());
            // Some llama.cpp builds only inspect the top-level flag.
            body.put("enable_thinking", opts.enableThinking());
        }
        if (!opts.enableThinking() && Boolean.TRUE.equals(config.getJsonFormat())) {
            if (opts.omitResponseFormat()) {
                return;
            }
            ObjectNode responseFormat = body.putObject("response_format");
            String type = StringUtils.defaultIfBlank(config.getJsonFormatType(), "json_object");
            if (!"json_schema".equalsIgnoreCase(type)) {
                responseFormat.put("type", type);
                return;
            }
            if (!jsonSchemaSupported) {
                responseFormat.put("type", "json_object");
                return;
            }
            if (!opts.hasJsonSchema()) {
                throw new IllegalArgumentException(
                        "json_schema response format requires a named schema definition");
            }
            responseFormat.put("type", "json_schema");
            ObjectNode jsonSchema = responseFormat.putObject("json_schema");
            jsonSchema.put("name", opts.jsonSchemaName());
            try {
                jsonSchema.set("schema", MAPPER.readTree(opts.jsonSchema()));
            } catch (Exception exception) {
                throw new IllegalArgumentException("json schema must be valid JSON", exception);
            }
        }
    }

    static void clearJsonSchemaCapabilitiesForTests() {
        JSON_SCHEMA_CAPABILITIES.clear();
    }

    private boolean isJsonSchemaRequest(ChatModelConfig config, ChatOptions options) {
        return !options.enableThinking() && options.hasJsonSchema()
                && Boolean.TRUE.equals(config.getJsonFormat())
                && "json_schema".equalsIgnoreCase(config.getJsonFormatType());
    }

    private JsonSchemaCapability resolveJsonSchemaCapability(ChatModelConfig config, String url) {
        JsonSchemaCapabilityKey key = new JsonSchemaCapabilityKey(url,
                StringUtils.defaultIfBlank(config.getModelName(), "local"));
        return JSON_SCHEMA_CAPABILITIES.computeIfAbsent(key,
                ignored -> probeJsonSchemaCapability(config, url));
    }

    private JsonSchemaCapability probeJsonSchemaCapability(ChatModelConfig config, String url) {
        try {
            ChatOptions options = ChatOptions.jsonSchema(JSON_SCHEMA_PROBE_NAME, JSON_SCHEMA_PROBE)
                    .withMaxTokens(16);
            ObjectNode body = createRequestBody(config, JSON_SCHEMA_PROBE_SYSTEM,
                    JSON_SCHEMA_PROBE_USER, options, true);
            HttpResponse<String> response = post(config, url, body, options);
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.info("OpenAI-compatible json_schema capability supported endpoint={} model={}", url,
                        StringUtils.defaultIfBlank(config.getModelName(), "local"));
                return JsonSchemaCapability.SUPPORTED;
            }
            if (response.statusCode() == 400 || response.statusCode() == 501) {
                LOG.warn("OpenAI-compatible json_schema capability unsupported endpoint={} model={} status={}; falling back to json_object",
                        url, StringUtils.defaultIfBlank(config.getModelName(), "local"),
                        response.statusCode());
                return JsonSchemaCapability.UNSUPPORTED;
            }
            throw JsonSchemaCapabilityException.unexpectedStatus(response.statusCode());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw JsonSchemaCapabilityException.probeFailed("interrupted", exception);
        } catch (JsonSchemaCapabilityException exception) {
            throw exception;
        } catch (Exception exception) {
            throw JsonSchemaCapabilityException.probeFailed("request_failed", exception);
        }
    }

    ObjectNode createRequestBody(ChatModelConfig config, String systemPrefix,
            String userContent, ChatOptions options, boolean jsonSchemaSupported) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", StringUtils.defaultIfBlank(config.getModelName(), "local"));
        boolean llamaCppExtensions = usesLlamaCppExtensions(config.getBaseUrl());
        if (llamaCppExtensions) {
            body.put("cache_prompt", true);
        }
        body.put("stream", false);
        if (config.getTemperature() != null) {
            body.put("temperature", config.getTemperature());
        } else {
            body.put("temperature", 0.0d);
        }
        if (config.getTopP() != null) {
            body.put("top_p", config.getTopP());
        }
        if (options.maxTokens() > 0) {
            body.put("max_tokens", options.maxTokens());
        }
        applyThinkingOptions(body, config, options, jsonSchemaSupported, llamaCppExtensions);
        if (llamaCppExtensions) {
            sanitizeProviderJsonSchema(body);
        } else {
            sanitizeOpenAiJsonSchema(body);
        }

        ArrayNode messages = body.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", systemPrefix);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userContent);
        return body;
    }

    /**
     * llama.cpp accepts the structural subset of JSON Schema used for constrained decoding, but
     * the Qwen endpoint rejects validation-only keywords such as {@code format}, {@code pattern}
     * and numeric bounds when they are nested below a closed object. The local contract parser is
     * still authoritative for those semantic checks, so keep the shape, required fields and
     * enumerations while removing only provider-incompatible assertions from the wire payload.
     */
    private static void sanitizeProviderJsonSchema(ObjectNode body) {
        JsonNode schema = body.path("response_format").path("json_schema").path("schema");
        if (!schema.isMissingNode()) {
            sanitizeProviderSchemaNode(schema);
        }
    }

    /**
     * Adapt the authored contract to the strict structured-output subset used by public
     * OpenAI-compatible endpoints. Strict providers require every declared property to appear in
     * {@code required}; fields that were optional in the authored contract are therefore made
     * nullable before they are added. This changes only the wire representation (absent becomes
     * explicit {@code null}) and preserves the business-level optionality seen by Jackson.
     */
    private static void sanitizeOpenAiJsonSchema(ObjectNode body) {
        sanitizeProviderJsonSchema(body);
        JsonNode jsonSchema = body.path("response_format").path("json_schema");
        if (!jsonSchema.isObject()) {
            return;
        }
        ((ObjectNode) jsonSchema).put("strict", true);
        normalizeStrictSchema(jsonSchema.path("schema"));
    }

    private static void normalizeStrictSchema(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(LlamaCppPrefixChatClient::normalizeStrictSchema);
            return;
        }
        if (!node.isObject()) {
            return;
        }

        ObjectNode object = (ObjectNode) node;
        normalizeStrictScalarSchema(object);
        JsonNode properties = object.path("properties");
        if (properties.isObject()) {
            java.util.LinkedHashSet<String> authoredRequired = new java.util.LinkedHashSet<>();
            JsonNode required = object.path("required");
            if (required.isArray()) {
                required.forEach(value -> {
                    if (value.isTextual()) {
                        authoredRequired.add(value.asText());
                    }
                });
            }

            ArrayNode strictRequired = object.putArray("required");
            properties.fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                JsonNode property = entry.getValue();
                normalizeStrictSchema(property);
                if (!authoredRequired.contains(name) && property.isObject()) {
                    makeNullable((ObjectNode) property);
                }
                strictRequired.add(name);
            });
        }

        object.fields().forEachRemaining(entry -> {
            if (!"properties".equals(entry.getKey()) && !"required".equals(entry.getKey())) {
                normalizeStrictSchema(entry.getValue());
            }
        });
    }

    private static void normalizeStrictScalarSchema(ObjectNode object) {
        if (object.has("const") && !object.has("enum")) {
            ArrayNode values = object.putArray("enum");
            values.add(object.get("const"));
            object.remove("const");
        }
        if (object.has("type") || !object.path("enum").isArray()
                || object.path("enum").isEmpty()) {
            return;
        }
        java.util.LinkedHashSet<String> types = new java.util.LinkedHashSet<>();
        object.path("enum").forEach(value -> types.add(jsonSchemaType(value)));
        if (types.size() == 1) {
            object.put("type", types.iterator().next());
        } else {
            ArrayNode typeArray = object.putArray("type");
            types.forEach(typeArray::add);
        }
    }

    private static void makeNullable(ObjectNode schema) {
        JsonNode type = schema.get("type");
        if (type != null && type.isTextual()) {
            ArrayNode types = MAPPER.createArrayNode();
            types.add(type.asText());
            types.add("null");
            schema.set("type", types);
        } else if (type != null && type.isArray()
                && !java.util.stream.StreamSupport.stream(type.spliterator(), false)
                        .anyMatch(value -> value.isTextual() && "null".equals(value.asText()))) {
            ((ArrayNode) type).add("null");
        }

        JsonNode values = schema.get("enum");
        if (values != null && values.isArray()
                && !java.util.stream.StreamSupport.stream(values.spliterator(), false)
                        .anyMatch(JsonNode::isNull)) {
            ((ArrayNode) values).addNull();
        }
    }

    private static String jsonSchemaType(JsonNode value) {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        if (value.isIntegralNumber()) {
            return "integer";
        }
        if (value.isFloatingPointNumber()) {
            return "number";
        }
        if (value.isArray()) {
            return "array";
        }
        if (value.isObject()) {
            return "object";
        }
        return "string";
    }

    private static void sanitizeProviderSchemaNode(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            object.remove(List.of("format", "pattern", "minimum", "maximum", "exclusiveMinimum",
                    "exclusiveMaximum", "minLength", "maxLength", "minItems", "maxItems",
                    "multipleOf"));
            object.fields().forEachRemaining(entry -> sanitizeProviderSchemaNode(entry.getValue()));
        } else if (node.isArray()) {
            node.forEach(LlamaCppPrefixChatClient::sanitizeProviderSchemaNode);
        }
    }

    private HttpResponse<String> post(ChatModelConfig config, String url, ObjectNode body,
            ChatOptions options) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofSeconds(resolveTimeoutSeconds(config, options)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        String apiKey = resolveApiKey(config);
        if (StringUtils.isNotBlank(apiKey)) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static long resolveTimeoutSeconds(ChatModelConfig config, ChatOptions options) {
        long configured =
                config.getTimeOut() == null || config.getTimeOut() <= 0 ? 0L : config.getTimeOut();
        return options.enableThinking() ? Math.max(configured, 300L)
                : (configured <= 0 ? 120L : configured);
    }

    private static IllegalStateException failedResponse(HttpResponse<String> response) {
        return new IllegalStateException(
                "OpenAI-compatible chat failed status=" + response.statusCode());
    }

    static final class JsonSchemaCapabilityException extends IllegalStateException {

        private JsonSchemaCapabilityException(String code, Throwable cause) {
            super("OpenAI-compatible json_schema capability probe failed code=" + code, cause);
        }

        static JsonSchemaCapabilityException unexpectedStatus(int statusCode) {
            return new JsonSchemaCapabilityException("unexpected_status_" + statusCode, null);
        }

        static JsonSchemaCapabilityException probeFailed(String code, Throwable cause) {
            return new JsonSchemaCapabilityException(code, cause);
        }
    }

    private enum JsonSchemaCapability {
        SUPPORTED, UNSUPPORTED
    }

    private record JsonSchemaCapabilityKey(String endpoint, String modelName) {}

    static String resolveChatCompletionsUrl(String baseUrl) {
        String root = baseUrl.trim();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        if (root.endsWith("/chat/completions")) {
            return root;
        }
        if (root.endsWith("/v1")) {
            return root + "/chat/completions";
        }
        return root + "/v1/chat/completions";
    }

    /** Only loopback and RFC1918 endpoints receive llama.cpp-specific request extensions. */
    static boolean usesLlamaCppExtensions(String baseUrl) {
        if (StringUtils.isBlank(baseUrl)) {
            return true;
        }
        String host;
        try {
            host = URI.create(baseUrl.trim()).getHost();
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if (StringUtils.isBlank(host)) {
            return false;
        }
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        if ("localhost".equals(normalized) || "::1".equals(normalized)
                || normalized.startsWith("127.")) {
            return true;
        }
        String[] octets = normalized.split("\\.");
        if (octets.length != 4) {
            return false;
        }
        try {
            int first = Integer.parseInt(octets[0]);
            int second = Integer.parseInt(octets[1]);
            return first == 10 || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168);
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    /**
     * Strip model thinking wrappers so downstream SQL extractors see only the final answer.
     */
    public static String stripThinking(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw;
        text = THINK_BLOCK.matcher(text).replaceAll("");
        text = THINK_BLOCK_ALT.matcher(text).replaceAll("");
        text = REDACTED_THINKING.matcher(text).replaceAll("");
        // Unclosed think block: drop everything up to end of think open tag content start
        Matcher unclosed = Pattern.compile("(?is)<think\\b[^>]*>").matcher(text);
        if (unclosed.find()) {
            int start = unclosed.start();
            int endTag = StringUtils.indexOfIgnoreCase(text, "</think>", start);
            if (endTag < 0) {
                text = text.substring(0, start);
            }
        }
        return text.strip();
    }

    private static String resolveApiKey(ChatModelConfig config) {
        try {
            String decrypted = config.keyDecrypt();
            if (StringUtils.isNotBlank(decrypted)) {
                return decrypted;
            }
        } catch (RuntimeException ignored) {
            // fall through to raw key
        }
        return config.getApiKey();
    }

    /**
     * Numeric timing keys that are safe to surface in logs and diagnostics. Anything else (e.g.
     * server echo fields) is dropped so prompts, results or credentials never leak through timings.
     */
    private static final java.util.Set<String> WHITELISTED_TIMING_KEYS = java.util.Set.of(
            "prompt_n", "cache_n", "predicted_n", "prompt_ms", "predicted_ms",
            "prompt_per_second", "predicted_per_second", "prompt_per_token_ms",
            "predicted_per_token_ms", "prompt_tokens", "completion_tokens");

    ChatResult parseResponse(String body, boolean thinkingEnabled) throws Exception {
        return parseResponse(body, thinkingEnabled, true);
    }

    private ChatResult parseResponse(String body, boolean thinkingEnabled,
            boolean cachePromptEnabled) throws Exception {
        JsonNode root = MAPPER.readTree(body);
        String content = null;
        String reasoning = null;
        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode message = choices.get(0).path("message");
            if (message.hasNonNull("content")) {
                content = message.get("content").asText();
            } else if (choices.get(0).hasNonNull("text")) {
                content = choices.get(0).get("text").asText();
            }
            if (message.hasNonNull("reasoning_content")) {
                reasoning = message.get("reasoning_content").asText();
            } else if (message.hasNonNull("reasoning")) {
                reasoning = message.get("reasoning").asText();
            }
        }
        if (StringUtils.isBlank(content) && root.hasNonNull("content")) {
            content = root.get("content").asText();
        }
        if (StringUtils.isBlank(content) && StringUtils.isBlank(reasoning)) {
            throw new IllegalStateException(
                    "llama.cpp response missing content: " + StringUtils.left(body, 300));
        }

        String rawContent = content == null ? "" : content;
        String stripped = stripThinking(rawContent);
        // Prefer content after think strip; if empty, keep original.
        String finalContent = StringUtils.isNotBlank(stripped) ? stripped : rawContent.strip();
        int reasoningChars = (reasoning == null ? 0 : reasoning.length())
                + Math.max(0, rawContent.length() - finalContent.length());

        Map<String, Object> timings = new LinkedHashMap<>();
        JsonNode timingsNode = root.path("timings");
        if (timingsNode.isObject()) {
            timingsNode.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (WHITELISTED_TIMING_KEYS.contains(entry.getKey()) && value.isNumber()) {
                    timings.put(entry.getKey(), value.numberValue());
                }
            });
        }
        if (root.path("usage").isObject()) {
            JsonNode usage = root.path("usage");
            if (usage.has("prompt_tokens")) {
                timings.putIfAbsent("prompt_tokens", usage.get("prompt_tokens").asInt());
            }
            if (usage.has("completion_tokens")) {
                timings.putIfAbsent("completion_tokens", usage.get("completion_tokens").asInt());
            }
        }
        timings.put("thinking_enabled", thinkingEnabled);
        timings.put("reasoning_chars", reasoningChars);

        int cacheN = numberAsInt(timings.get("cache_n"));
        int promptN = numberAsInt(timings.get("prompt_n"));
        KEY_PIPELINE.info(
                "LlamaCppPrefixChatClient response cache_prompt={} thinking={} cache_n={} prompt_n={} reasoningChars={} timings={}",
                cachePromptEnabled, thinkingEnabled, cacheN, promptN, reasoningChars, timings);
        if (cacheN > 0) {
            KEY_PIPELINE.info("LlamaCppPrefixChatClient REAL prefix/KV hit cache_n={}", cacheN);
        } else {
            LOG.debug("llama.cpp timings without cache_n (server may omit stats): {}", timings);
        }
        return new ChatResult(finalContent, timings, cachePromptEnabled, thinkingEnabled,
                reasoningChars);
    }

    private static int numberAsInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }
}
