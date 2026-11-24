package com.expense_management_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code chatbot.*} namespace: the Paves Assistant's AI backend (see {@code ChatbotController}).
 * <p>
 * Two kinds of LLM are supported, chosen by {@code provider}:
 * <ul>
 *   <li>{@code openai-compatible}: a hosted API speaking the OpenAI chat-completions format, e.g. Groq,
 *       Google Gemini (OpenAI endpoint), OpenRouter, Cerebras, Mistral. Many have a free tier. Needs
 *       {@code apiBaseUrl} and {@code apiKey}. Employee data is sent to that provider.</li>
 *   <li>{@code ollama}: a self-hosted <a href="https://ollama.com">Ollama</a> server. Free, no key, data
 *       stays on our servers, but needs CPU/RAM and disk for the model. Needs {@code ollamaBaseUrl}.</li>
 * </ul>
 *
 * @param enabled        master switch; when false {@code POST /xms/chat} answers 502 without calling the LLM
 * @param provider       {@code openai-compatible} or {@code ollama}
 * @param apiBaseUrl     OpenAI-compatible base URL (the part before {@code /chat/completions})
 * @param apiKey         API key for the OpenAI-compatible provider; secret, from {@code xms-secrets}
 * @param ollamaBaseUrl  root URL of the Ollama server, e.g. {@code http://localhost:11434}
 * @param model          model name at the provider; must support tool (function) calling
 * @param temperature    sampling temperature; kept low so answers stick to the data
 * @param timeoutSeconds how long to wait for one LLM call before giving up
 * @param maxToolRounds  how many rounds of tool calls the model may make before it must answer
 * @param maxHistory     how many of the most recent chat messages are sent to the model
 */
@ConfigurationProperties(prefix = "chatbot")
public record ChatbotProperties(
        boolean enabled,
        String provider,
        String apiBaseUrl,
        String apiKey,
        String ollamaBaseUrl,
        String model,
        double temperature,
        int timeoutSeconds,
        int maxToolRounds,
        int maxHistory) {
}
