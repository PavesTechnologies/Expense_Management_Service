package com.expense_management_service.integration.ums.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Envelope returned by UMS's {@code GET /admin/users}: {@code {"total": N, "users": [...]}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UmsUsersResponse(
        int total,
        List<UmsUserResponse> users
) {
}
