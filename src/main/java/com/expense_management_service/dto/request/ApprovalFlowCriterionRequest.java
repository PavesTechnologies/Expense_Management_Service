package com.expense_management_service.dto.request;

import com.expense_management_service.enums.CriterionField;
import com.expense_management_service.enums.CriterionOperator;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ApprovalFlowCriterionRequest(
        @NotNull Integer index,
        @NotNull CriterionField field,
        @NotNull CriterionOperator operator,
        @Size(max = 255) String value
) {
}
