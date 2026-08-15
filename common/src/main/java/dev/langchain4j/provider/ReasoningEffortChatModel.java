package dev.langchain4j.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Minimal OpenAI-compatible chat model that always sends reasoning_effort
 * (e.g. "none") in the request body.  LangChain4j 0.36 OpenAiChatModel cannot
 * send that parameter, and gateways like OpenCode Zen/Go only honor it (they
 * ignore llama.cpp-style enable_thinking).  Used when system property
 * {@code s2.llm.openai.reasoning-effort} is set.
 */
public class ReasoningEffortChatModel implements ChatLanguageModel {

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String endpoint;
    private final String apiKey;
    private final String modelName;
    private final Double temperature;
    private final String reasoningEffort;

    public ReasoningEffortChatModel(ChatModelConfig config, String reasoningEffort) {
        String root = config.getBaseUrl().trim();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        this.endpoint = root.endsWith("/chat/completions") ? root : root + "/chat/completions";
        this.apiKey = config.keyDecrypt();
        this.modelName = config.getModelName();
        this.temperature = config.getTemperature();
        this.reasoningEffort = reasoningEffort;
        long timeoutSeconds = config.getTimeOut() == null ? 60L : config.getTimeOut();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
    }

    @Override
    public String generate(String userMessage) {
        return generate(UserMessage.from(userMessage)).content().text();
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        try {
            var body = mapper.createObjectNode();
            body.put("model", modelName);
            if (temperature != null) {
                body.put("temperature", temperature);
            }
            if (reasoningEffort != null && !reasoningEffort.isBlank()) {
                body.put("reasoning_effort", reasoningEffort);
            }
            var msgs = body.putArray("messages");
            for (ChatMessage message : messages) {
                var node = msgs.addObject();
                if (message instanceof SystemMessage) {
                    node.put("role", "system").put("content", ((SystemMessage) message).text());
                } else if (message instanceof AiMessage) {
                    node.put("role", "assistant").put("content", ((AiMessage) message).text());
                } else {
                    node.put("role", "user").put("content", ((UserMessage) message).singleText());
                }
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(response.body());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "chat request failed status=" + response.statusCode() + " body="
                                + response.body());
            }
            String content = root.path("choices").path(0).path("message").path("content")
                    .asText("");
            return Response.from(AiMessage.from(content));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("chat request failed", e);
        }
    }
}
