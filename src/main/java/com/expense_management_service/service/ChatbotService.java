package com.expense_management_service.service;

import com.expense_management_service.dto.request.ChatRequest;
import com.expense_management_service.dto.response.ChatResponse;

/** The Paves Assistant's AI answers, backed by a self-hosted LLM with read-only tools over the caller's data. */
public interface ChatbotService {

    ChatResponse chat(ChatRequest request);
}
