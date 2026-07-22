package com.expense_management_service.integration.ums.dto;

import java.util.List;
import java.util.UUID;

/**
 * Shape of a UMS user profile response.
 * <p>
 * Field names mirror the JWT claims used elsewhere in XMS so mapping stays
 * consistent between token-derived {@code CurrentUser} data and data fetched
 * directly from UMS.
 */
public record UserProfileResponse(
        UUID uuid,
        String employeeId,
        String email,
        String name,
        List<String> roles,
        List<String> permissions
) {
}
