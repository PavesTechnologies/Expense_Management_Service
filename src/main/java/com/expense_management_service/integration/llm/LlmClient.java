package com.expense_management_service.integration.llm;

import java.util.List;
import java.util.Map;

/**
 * A chat-completion LLM. Messages and tool definitions use the common chat shape
 * ({@code {role, content}} and {@code {type: "function", function: {...}}}); each implementation
 * builds the provider-specific parts (assistant tool-call messages, tool results) itself.
 * The active implementation is chosen by {@code chatbot.provider}.
 */
public interface LlmClient {

    /**
     * Sends one chat turn.
     *
     * @param messages the full conversation so far, system prompt first
     * @param tools    tool definitions the model may call; empty to force a plain answer
     * @return the model's reply: either text, or tool calls to run before asking again
     */
    LlmReply chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools);

    /** The message that carries one tool's result back to the model, in this provider's format. */
    Map<String, Object> toolResultMessage(ToolCall call, String content);

    /**
     * One tool call requested by the model.
     *
     * @param id provider's call id (OpenAI-compatible APIs require it on the result); null for Ollama
     */
    record ToolCall(String id, String name, Map<String, Object> arguments) {
    }

    /**
     * @param content          the text answer (may be blank when the model only asks for tools)
     * @param toolCalls        tools the model wants run; empty when it has answered
     * @param assistantMessage the assistant turn to append back into the conversation before the tool results
     */
    record LlmReply(String content, List<ToolCall> toolCalls, Map<String, Object> assistantMessage) {
    }
}
