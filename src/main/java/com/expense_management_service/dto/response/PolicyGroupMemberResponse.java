package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record PolicyGroupMemberResponse(
        UUID memberId,
        UUID groupId,
        String employeeId,
        LocalDateTime createdAt
) {
}
