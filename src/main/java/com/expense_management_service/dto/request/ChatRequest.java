package com.expense_management_service.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * One Paves Assistant question, with the recent conversation. Sent by the intranet's chat widget
 * ({@code src/components/Chatbot/chatApi.js}).
 *
 * @param messages the conversation so far, oldest first; the last one is the new question
 * @param context  where the user is in the app; optional
 */
public record ChatRequest(
        @NotEmpty @Size(max = 60) List<@Valid Message> messages,
        Context context) {

    public record Message(
            @NotBlank @Pattern(regexp = "user|assistant") String role,
            @NotBlank @Size(max = 4000) String content) {
    }

    /** Only used to phrase the answer; never trusted for identity (that comes from the JWT). */
    public record Context(
            @Size(max = 200) String userName,
            @Size(max = 300) String currentPage,
            @Size(max = 100) String module) {
    }
}
