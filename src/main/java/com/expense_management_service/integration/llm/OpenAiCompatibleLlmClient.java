package com.expense_management_service.integration.llm;

import com.expense_management_service.common.exception.IntegrationUnavailableException;
import com.expense_management_service.config.ChatbotProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link LlmClient} for any hosted API that speaks the OpenAI chat-completions format
 * ({@code POST {apiBaseUrl}/chat/completions}): Groq, Google Gemini's OpenAI endpoint, OpenRouter,
 * Cerebras, Mistral, GitHub Models, a paid OpenAI-style provider, etc. Switching between them is
 * configuration only: {@code chatbot.api-base-url}, {@code chatbot.api-key}, {@code chatbot.model}.
 * <p>
 * The API key is sent only as the {@code Authorization} header and never logged; neither are
 * message contents, which carry employee and financial data. Active when
 * {@code chatbot.provider=openai-compatible} (the default).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "chatbot.provider", havingValue = "openai-compatible", matchIfMissing = true)
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ChatbotProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public OpenAiCompatibleLlmClient(ChatbotProperties properties) {
        this.properties = properties;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(properties.timeoutSeconds(), 5)));
        String baseUrl = properties.apiBaseUrl() == null ? "" : properties.apiBaseUrl().replaceAll("/+$", "");
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public LlmReply chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IntegrationUnavailableException(
                    "The assistant's AI is not configured: set CHATBOT_API_KEY.", null);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        body.put("temperature", properties.temperature());
        body.put("stream", false);

        String raw;
        try {
            raw = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            log.warn("[LLM-OUT] {} (model {}) answered HTTP {}", properties.apiBaseUrl(), properties.model(), status);
            if (status == 429) {
                throw new IntegrationUnavailableException(
                        "The assistant is busy right now (AI usage limit reached). Please try again in a minute.", ex);
            }
            if (status == 401 || status == 403) {
                throw new IntegrationUnavailableException(
                        "The assistant's AI rejected its API key. Check CHATBOT_API_KEY.", ex);
            }
            throw new IntegrationUnavailableException("The assistant's AI service returned an error.", ex);
        } catch (RestClientException ex) {
            log.warn("[LLM-OUT] call to {} (model {}) failed: {}", properties.apiBaseUrl(), properties.model(), ex.getMessage());
            throw new IntegrationUnavailableException("The assistant's AI service is not reachable right now.", ex);
        } catch (Exception ex) {
            throw new IntegrationUnavailableException("Could not prepare the request for the assistant's AI service.", ex);
        }
        return parse(raw);
    }

    @Override
    public Map<String, Object> toolResultMessage(ToolCall call, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", call.id());
        message.put("content", content);
        return message;
    }

    private LlmReply parse(String raw) {
        Map<String, Object> response;
        try {
            response = objectMapper.readValue(raw == null ? "{}" : raw, MAP_TYPE);
        } catch (Exception ex) {
            throw new IntegrationUnavailableException("The assistant's AI service sent an unreadable reply.", ex);
        }
        if (!(response.get("choices") instanceof List<?> choices) || choices.isEmpty()
                || !(choices.get(0) instanceof Map<?, ?> choice)
                || !(choice.get("message") instanceof Map<?, ?> message)) {
            throw new IntegrationUnavailableException("The assistant's AI service sent an empty reply.", null);
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        List<Map<String, Object>> echoedCalls = new ArrayList<>();
        if (message.get("tool_calls") instanceof List<?> calls) {
            int index = 0;
            for (Object call : calls) {
                if (!(call instanceof Map<?, ?> callMap) || !(callMap.get("function") instanceof Map<?, ?> function)) {
                    continue;
                }
                Object name = function.get("name");
                if (name == null) continue;
                String id = callMap.get("id") != null ? String.valueOf(callMap.get("id")) : "call_" + index;
                index++;
                Object arguments = function.get("arguments");
                toolCalls.add(new ToolCall(id, String.valueOf(name), readArguments(arguments)));
                // Echo the call back exactly as required (arguments as a JSON string), without extra provider fields.
                echoedCalls.add(Map.of("id", id, "type", "function", "function", Map.of(
                        "name", String.valueOf(name),
                        "arguments", arguments instanceof String s ? s : writeJson(arguments))));
            }
        }

        Object content = message.get("content");
        Map<String, Object> assistantMessage = new LinkedHashMap<>();
        assistantMessage.put("role", "assistant");
        if (content != null) assistantMessage.put("content", String.valueOf(content));
        if (!echoedCalls.isEmpty()) assistantMessage.put("tool_calls", echoedCalls);

        return new LlmReply(content == null ? "" : String.valueOf(content), toolCalls, assistantMessage);
    }

    /** OpenAI-style APIs send arguments as a JSON string; tolerate an object too. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readArguments(Object arguments) {
        if (arguments instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (arguments instanceof String text && !text.isBlank()) {
            try {
                return objectMapper.readValue(text, MAP_TYPE);
            } catch (Exception ignored) {
                // fall through: treat unreadable arguments as none
            }
        }
        return Map.of();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
