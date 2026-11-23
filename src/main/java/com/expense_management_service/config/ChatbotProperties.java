package com.expense_management_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code chatbot.*} namespace: the Paves Assistant's AI backend (see {@code ChatbotController}).
 * <p>
 * The LLM is a free, self-hosted <a href="https://ollama.com">Ollama</a> server, so no API key or paid
 * account is involved and employee data never leaves our infrastructure.
 *
 * @param enabled        master switch; when false {@code POST /xms/chat} answers 502 without calling the LLM
 * @param ollamaBaseUrl  root URL of the Ollama server, e.g. {@code http://localhost:11434}
 * @param model          Ollama model tag; must support tool calling (e.g. {@code qwen2.5:7b}, {@code llama3.1:8b})
 * @param temperature    sampling temperature; kept low so answers stick to the data
 * @param timeoutSeconds how long to wait for one LLM call before giving up
 * @param maxToolRounds  how many rounds of tool calls the model may make before it must answer
 * @param maxHistory     how many of the most recent chat messages are sent to the model
 */
@ConfigurationProperties(prefix = "chatbot")
public record ChatbotProperties(
        boolean enabled,
        String ollamaBaseUrl,
        String model,
        double temperature,
        int timeoutSeconds,
        int maxToolRounds,
        int maxHistory) {
}
