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
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Direct llama.cpp OpenAI-compatible chat client with true prompt/prefix caching.
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

    private static final Pattern THINK_BLOCK = Pattern.compile("(?is)<think\\b[^>]*>.*?</think>");
    private static final Pattern THINK_BLOCK_ALT =
            Pattern.compile("(?is)<thinking\\b[^>]*>.*?</thinking>");
    private static final Pattern REDACTED_THINKING =
            Pattern.compile("(?is)<\\|?redacted_thinking\\|?>.*?<\\/?redacted_thinking\\|?>");

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public record ChatOptions(boolean enableThinking, int maxTokens) {
        public static ChatOptions defaults() {
            return new ChatOptions(false, 0);
        }

        /**
         * Options for warming a fixed prompt prefix. The warm-up response is discarded, so it
         * should consume the smallest possible completion while still exercising the chat template.
         */
        public static ChatOptions warmup(boolean thinkingEnabled) {
            return new ChatOptions(thinkingEnabled, thinkingEnabled ? 1024 : 1);
        }

        public static ChatOptions thinking(int maxTokens) {
            return new ChatOptions(true, Math.max(1024, maxTokens));
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
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", StringUtils.defaultIfBlank(config.getModelName(), "local"));
        body.put("cache_prompt", true);
        body.put("stream", false);
        if (config.getTemperature() != null) {
            body.put("temperature", config.getTemperature());
        } else {
            body.put("temperature", 0.0d);
        }
        if (config.getTopP() != null) {
            body.put("top_p", config.getTopP());
        }
        if (opts.maxTokens() > 0) {
            body.put("max_tokens", opts.maxTokens());
        }

        applyThinkingOptions(body, config, opts);

        ArrayNode messages = body.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", systemPrefix);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userContent);

        // Thinking runs longer; default timeout floors higher when enabled.
        long configured =
                config.getTimeOut() == null || config.getTimeOut() <= 0 ? 0L : config.getTimeOut();
        long timeoutSec = opts.enableThinking() ? Math.max(configured, 300L)
                : (configured <= 0 ? 120L : configured);
        try {
            String json = MAPPER.writeValueAsString(body);
            HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            String apiKey = resolveApiKey(config);
            if (StringUtils.isNotBlank(apiKey)) {
                request.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response =
                    httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "llama.cpp chat failed status=" + response.statusCode() + " body="
                                + StringUtils.left(response.body(), 300));
            }
            return parseResponse(response.body(), opts.enableThinking());
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
        ChatOptions opts = options == null ? ChatOptions.defaults() : options;
        ObjectNode templateKwargs = body.putObject("chat_template_kwargs");
        templateKwargs.put("enable_thinking", opts.enableThinking());
        // Some llama.cpp builds only inspect the top-level flag.
        body.put("enable_thinking", opts.enableThinking());
        // OpenAI-compatible gateways (e.g. OpenCode Zen/Go) ignore chat_template_kwargs;
        // reasoning_effort is the switch they honor.  Thinking off => no reasoning at all,
        // which cuts answer latency from minutes to seconds on reasoning models.
        if (!opts.enableThinking()) {
            body.put("reasoning_effort", "none");
        }
        if (!opts.enableThinking() && Boolean.TRUE.equals(config.getJsonFormat())) {
            ObjectNode responseFormat = body.putObject("response_format");
            String type = StringUtils.defaultIfBlank(config.getJsonFormatType(), "json_object");
            responseFormat.put("type", "json_schema".equalsIgnoreCase(type) ? "json_object" : type);
        }
    }

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

    private ChatResult parseResponse(String body, boolean thinkingEnabled) throws Exception {
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
                if (value.isNumber()) {
                    timings.put(entry.getKey(), value.numberValue());
                } else if (value.isTextual()) {
                    timings.put(entry.getKey(), value.asText());
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
                "LlamaCppPrefixChatClient response cache_prompt=true thinking={} cache_n={} prompt_n={} reasoningChars={} timings={}",
                thinkingEnabled, cacheN, promptN, reasoningChars, timings);
        if (cacheN > 0) {
            KEY_PIPELINE.info("LlamaCppPrefixChatClient REAL prefix/KV hit cache_n={}", cacheN);
        } else {
            LOG.debug("llama.cpp timings without cache_n (server may omit stats): {}", timings);
        }
        return new ChatResult(finalContent, timings, true, thinkingEnabled, reasoningChars);
    }

    private static int numberAsInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }
}
