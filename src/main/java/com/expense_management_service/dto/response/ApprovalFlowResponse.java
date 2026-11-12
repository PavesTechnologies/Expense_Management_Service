package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ApprovalFlowResponse(
        UUID flowId,
        String name,
        Integer priority,
        String criteriaPattern,
        Boolean isCatchAll,
        String status,
        List<ApprovalFlowCriterionResponse> criteria,
        List<ApprovalLevelResponse> levels,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
