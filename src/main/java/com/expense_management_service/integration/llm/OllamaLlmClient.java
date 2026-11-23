package com.expense_management_service.integration.llm;

import com.expense_management_service.common.exception.IntegrationUnavailableException;
import com.expense_management_service.config.ChatbotProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link LlmClient} backed by a self-hosted Ollama server ({@code POST /api/chat}, non-streaming).
 * <p>
 * Bodies are (de)serialized with a local Jackson 2 {@link ObjectMapper} and sent as strings, the same
 * way other integration code here avoids depending on which Jackson version Spring's converters use.
 * Message contents are never logged: they carry employee and financial data.
 */
@Slf4j
@Component
public class OllamaLlmClient implements LlmClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ChatbotProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public OllamaLlmClient(ChatbotProperties properties) {
        this.properties = properties;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(properties.timeoutSeconds(), 5)));
        this.restClient = RestClient.builder()
                .baseUrl(properties.ollamaBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public LlmReply chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }
        body.put("stream", false);
        body.put("options", Map.of("temperature", properties.temperature()));

        String raw;
        try {
            raw = restClient.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException ex) {
            log.warn("[LLM-OUT] Ollama call to {} (model {}) failed: {}",
                    properties.ollamaBaseUrl(), properties.model(), ex.getMessage());
            throw new IntegrationUnavailableException("The assistant's AI service is not reachable right now.", ex);
        } catch (Exception ex) {
            throw new IntegrationUnavailableException("Could not prepare the request for the assistant's AI service.", ex);
        }
        return parse(raw);
    }

    @SuppressWarnings("unchecked")
    private LlmReply parse(String raw) {
        Map<String, Object> response;
        try {
            response = objectMapper.readValue(raw == null ? "{}" : raw, MAP_TYPE);
        } catch (Exception ex) {
            throw new IntegrationUnavailableException("The assistant's AI service sent an unreadable reply.", ex);
        }
        if (!(response.get("message") instanceof Map<?, ?> message)) {
            throw new IntegrationUnavailableException("The assistant's AI service sent an empty reply.", null);
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        if (message.get("tool_calls") instanceof List<?> calls) {
            for (Object call : calls) {
                if (call instanceof Map<?, ?> callMap && callMap.get("function") instanceof Map<?, ?> function) {
                    Object name = function.get("name");
                    if (name == null) continue;
                    toolCalls.add(new ToolCall(String.valueOf(name), readArguments(function.get("arguments"))));
                }
            }
        }
        Object content = message.get("content");
        return new LlmReply(content == null ? "" : String.valueOf(content), toolCalls, (Map<String, Object>) message);
    }

    /** Ollama sends arguments as an object; some models send a JSON string instead. */
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
}
