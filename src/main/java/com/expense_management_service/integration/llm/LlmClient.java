package com.expense_management_service.integration.llm;

import java.util.List;
import java.util.Map;

/**
 * A chat-completion LLM. Messages and tool definitions use the Ollama/OpenAI chat shape
 * ({@code {role, content, tool_calls?}} and {@code {type: "function", function: {...}}}).
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

    /** One tool call requested by the model. */
    record ToolCall(String name, Map<String, Object> arguments) {
    }

    /**
     * @param content    the text answer (may be blank when the model only asks for tools)
     * @param toolCalls  tools the model wants run; empty when it has answered
     * @param rawMessage the assistant message exactly as returned, to append back into the conversation
     */
    record LlmReply(String content, List<ToolCall> toolCalls, Map<String, Object> rawMessage) {
    }
}
