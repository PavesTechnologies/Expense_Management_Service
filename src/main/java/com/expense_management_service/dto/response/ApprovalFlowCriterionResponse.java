package com.expense_management_service.dto.response;

import com.expense_management_service.enums.CriterionField;
import com.expense_management_service.enums.CriterionOperator;

import java.util.UUID;

public record ApprovalFlowCriterionResponse(
        UUID criterionId,
        Integer index,
        CriterionField field,
        CriterionOperator operator,
        String value
) {
}
