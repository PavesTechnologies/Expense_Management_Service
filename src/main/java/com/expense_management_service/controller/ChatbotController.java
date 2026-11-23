package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ChatRequest;
import com.expense_management_service.dto.response.ChatResponse;
import com.expense_management_service.service.ChatbotService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI answers for the intranet's Paves Assistant chat widget. The frontend calls this as
 * {@code CHATBOT_AI_URL} for questions its built-in rules can't answer.
 */
@RestController
@RequestMapping("/xms/chat")
@RequiredArgsConstructor
public class ChatbotController {

    private final ChatbotService chatbotService;

    @Operation(summary = "Ask the Paves Assistant (self-hosted LLM with read-only access to the caller's expense data)")
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.success(chatbotService.chat(request));
    }
}
