package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.IntegrationUnavailableException;
import com.expense_management_service.config.ChatbotProperties;
import com.expense_management_service.dto.request.ChatRequest;
import com.expense_management_service.dto.response.ChatResponse;
import com.expense_management_service.integration.llm.LlmClient;
import com.expense_management_service.integration.llm.LlmClient.LlmReply;
import com.expense_management_service.integration.llm.LlmClient.ToolCall;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatbotServiceImplTest {

    private final LlmClient llmClient = mock(LlmClient.class);
    private final ChatbotTools tools = mock(ChatbotTools.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);

    private static final List<Map<String, Object>> TOOL_DEFS =
            List.of(Map.of("type", "function", "function", Map.of("name", ChatbotTools.MY_EXPENSE_REPORTS)));

    @BeforeEach
    void setUp() {
        when(currentUserService.getCurrentUser()).thenReturn(new CurrentUser(
                UUID.randomUUID(), 1L, "EMP-1", "asha@example.com", "Asha", List.of("General"), List.of()));
        when(tools.definitions()).thenReturn(TOOL_DEFS);
        when(llmClient.toolResultMessage(any(), any())).thenAnswer(inv -> {
            ToolCall call = inv.getArgument(0);
            return Map.of("role", "tool", "tool_call_id", call.id(), "content", inv.getArgument(1));
        });
    }

    private ChatbotServiceImpl service(boolean enabled, int maxToolRounds) {
        return new ChatbotServiceImpl(llmClient, tools,
                new ChatbotProperties(enabled, "openai-compatible", "https://api.example.invalid/v1", "test-key",
                        "http://localhost:11434", "test-model", 0.2, 60, maxToolRounds, 12),
                currentUserService, "Asia/Kolkata");
    }

    private static ChatRequest ask(String question) {
        return new ChatRequest(List.of(new ChatRequest.Message("user", question)),
                new ChatRequest.Context("Asha", "/expense-management/expenses/my", "Expense Management"));
    }

    @Test
    void answersDirectly_whenModelNeedsNoTools() {
        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(new LlmReply("Create a claim from Create Expense.", List.of(), Map.of("role", "assistant")));

        ChatResponse response = service(true, 3).chat(ask("How do I claim an expense?"));

        assertThat(response.reply()).isEqualTo("Create a claim from Create Expense.");
        verify(tools, never()).execute(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void runsRequestedTool_andSendsResultBackToModel() {
        Map<String, Object> toolAsk = Map.of("role", "assistant", "content", "",
                "tool_calls", List.of(Map.of("function", Map.of("name", ChatbotTools.MY_EXPENSE_REPORTS))));
        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(new LlmReply("", List.of(new ToolCall("call_1", ChatbotTools.MY_EXPENSE_REPORTS, Map.of())), toolAsk))
                .thenReturn(new LlmReply("Your claim ER-001 is approved.", List.of(), Map.of("role", "assistant")));
        when(tools.execute(eq(ChatbotTools.MY_EXPENSE_REPORTS), any()))
                .thenReturn("{\"reports\":[{\"reportNumber\":\"ER-001\",\"status\":\"APPROVED\"}]}");

        ChatResponse response = service(true, 3).chat(ask("Status of my claim?"));

        assertThat(response.reply()).isEqualTo("Your claim ER-001 is approved.");
        ArgumentCaptor<List<Map<String, Object>>> sent = ArgumentCaptor.forClass(List.class);
        verify(llmClient, times(2)).chat(sent.capture(), anyList());
        List<Map<String, Object>> secondCall = new ArrayList<>(sent.getAllValues().get(1));
        Map<String, Object> last = secondCall.get(secondCall.size() - 1);
        assertThat(last).containsEntry("role", "tool").containsEntry("tool_call_id", "call_1");
        assertThat((String) last.get("content")).contains("ER-001");
    }

    @Test
    @SuppressWarnings("unchecked")
    void systemPromptComesFirst_andNamesTheUser() {
        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(new LlmReply("Hi Asha!", List.of(), Map.of("role", "assistant")));

        service(true, 3).chat(ask("hi"));

        ArgumentCaptor<List<Map<String, Object>>> sent = ArgumentCaptor.forClass(List.class);
        verify(llmClient).chat(sent.capture(), eq(TOOL_DEFS));
        Map<String, Object> first = sent.getValue().get(0);
        assertThat(first).containsEntry("role", "system");
        assertThat((String) first.get("content")).contains("Asha").contains("Paves Assistant");
    }

    @Test
    void stopsOfferingTools_afterMaxRounds() {
        LlmReply wantsTool = new LlmReply("", List.of(new ToolCall("call_2", ChatbotTools.MY_CASH_ADVANCES, Map.of())),
                Map.of("role", "assistant", "content", ""));
        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(wantsTool)
                .thenReturn(new LlmReply("You have one open advance.", List.of(), Map.of("role", "assistant")));
        when(tools.execute(any(), any())).thenReturn("{}");

        ChatResponse response = service(true, 1).chat(ask("my advances"));

        assertThat(response.reply()).isEqualTo("You have one open advance.");
        verify(llmClient).chat(anyList(), eq(TOOL_DEFS));
        verify(llmClient).chat(anyList(), eq(List.of()));
    }

    @Test
    void returnsFallback_whenModelAnswersBlank() {
        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(new LlmReply("   ", List.of(), Map.of("role", "assistant")));

        assertThat(service(true, 3).chat(ask("??")).reply()).isEqualTo(ChatbotServiceImpl.FALLBACK_REPLY);
    }

    @Test
    void refuses_whenDisabled() {
        assertThatThrownBy(() -> service(false, 3).chat(ask("hi")))
                .isInstanceOf(IntegrationUnavailableException.class);
        verifyNoInteractions(llmClient);
    }
}
