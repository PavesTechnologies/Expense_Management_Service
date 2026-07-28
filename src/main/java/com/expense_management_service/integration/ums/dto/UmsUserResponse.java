package com.expense_management_service.integration.ums.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/** Shape of a user record returned by UMS's {@code GET /admin/users} list. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UmsUserResponse(
        @JsonProperty("user_uuid") UUID userUuid,
        @JsonProperty("user_id") Long userId,
        @JsonProperty("first_name") String firstName,
        @JsonProperty("last_name") String lastName,
        String mail,
        @JsonProperty("is_active") boolean isActive
) {
}
