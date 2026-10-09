package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Finance raises a query on one line item without rejecting the whole report - reason is required, mirrors {@code RejectReportRequest}. */
public record FinanceQueryRequest(
        @NotBlank @Size(max = 4000) String reason
) {
}
