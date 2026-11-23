package com.expense_management_service.dto.request;

import jakarta.validation.constraints.Size;

/**
 * Optional body of the submit call. {@code lateSubmissionReason} is needed only when the report is past its submission
 * deadline: the employee explains the delay and the report is submitted with that reason recorded.
 */
public record SubmitReportRequest(
        @Size(max = 2000) String lateSubmissionReason
) {
}
