package com.expense_management_service.integration.llm;

import com.expense_management_service.common.exception.IntegrationUnavailableException;
import com.expense_management_service.config.ChatbotProperties;
import com.expense_management_service.integration.llm.LlmClient.LlmReply;
import com.expense_management_service.integration.llm.LlmClient.ToolCall;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the client against a throwaway local HTTP server standing in for Groq/Gemini/etc. */
class OpenAiCompatibleLlmClientTest {

    private HttpServer server;
    private final AtomicReference<String> seenPath = new AtomicReference<>();
    private final AtomicReference<String> seenAuth = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private OpenAiCompatibleLlmClient clientAnswering(int status, String json, String apiKey) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenPath.set(exchange.getRequestURI().getPath());
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/openai/v1/";
        return new OpenAiCompatibleLlmClient(new ChatbotProperties(
                true, "openai-compatible", baseUrl, apiKey, null, "test-model", 0.2, 10, 3, 12));
    }

    @Test
    void sendsOpenAiRequest_andReadsTextAnswer() throws Exception {
        var client = clientAnswering(200, """
                {"choices":[{"message":{"role":"assistant","content":"Hello Asha!"}}]}""", "secret-key");

        LlmReply reply = client.chat(List.of(Map.of("role", "user", "content", "hi")), List.of());

        assertThat(reply.content()).isEqualTo("Hello Asha!");
        assertThat(reply.toolCalls()).isEmpty();
        assertThat(seenPath.get()).isEqualTo("/openai/v1/chat/completions");
        assertThat(seenAuth.get()).isEqualTo("Bearer secret-key");
        assertThat(seenBody.get()).contains("\"model\":\"test-model\"").doesNotContain("\"tools\"");
    }

    @Test
    void readsToolCalls_andBuildsMatchingToolResult() throws Exception {
        var client = clientAnswering(200, """
                {"choices":[{"message":{"role":"assistant","content":null,"reasoning":"internal",
                  "tool_calls":[{"id":"call_abc","type":"function",
                    "function":{"name":"get_my_cash_advances","arguments":"{\\"status\\":\\"DISBURSED\\"}"}}]}}]}""",
                "secret-key");

        LlmReply reply = client.chat(List.of(Map.of("role", "user", "content", "my advances")),
                List.of(Map.of("type", "function", "function", Map.of("name", "get_my_cash_advances"))));

        assertThat(reply.toolCalls()).containsExactly(
                new ToolCall("call_abc", "get_my_cash_advances", Map.of("status", "DISBURSED")));
        assertThat(reply.assistantMessage()).containsKey("tool_calls").doesNotContainKey("reasoning")
                .doesNotContainKey("content");
        assertThat(client.toolResultMessage(reply.toolCalls().get(0), "{}"))
                .containsEntry("role", "tool").containsEntry("tool_call_id", "call_abc");
        assertThat(seenBody.get()).contains("\"tools\"").contains("\"tool_choice\":\"auto\"");
    }

    @Test
    void rateLimit_becomesFriendlyUnavailableError() throws Exception {
        var client = clientAnswering(429, "{\"error\":{\"message\":\"rate limit\"}}", "secret-key");

        assertThatThrownBy(() -> client.chat(List.of(Map.of("role", "user", "content", "hi")), List.of()))
                .isInstanceOf(IntegrationUnavailableException.class)
                .hasMessageContaining("busy");
    }

    @Test
    void missingApiKey_failsWithoutCallingProvider() throws Exception {
        var client = clientAnswering(200, "{}", " ");

        assertThatThrownBy(() -> client.chat(List.of(Map.of("role", "user", "content", "hi")), List.of()))
                .isInstanceOf(IntegrationUnavailableException.class)
                .hasMessageContaining("CHATBOT_API_KEY");
        assertThat(seenPath.get()).isNull();
    }
}
