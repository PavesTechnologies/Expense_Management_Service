package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record ExpenseReportResponse(
        UUID reportId,
        String reportNumber,
        String employeeId,
        String title,
        String businessPurpose,
        String fiscalYear,
        UUID costCenterId,
        String costCenterName,
        String reportStatus,
        String paymentRoutingStatus,
        String invoiceHandoffStatus,
        UUID currencyId,
        String currencyCode,
        /** Currency of {@code totalAmount} - the organization base currency, not the report's own {@code currencyCode}. */
        String baseCurrencyCode,
        BigDecimal totalAmount,
        BigDecimal reimbursableAmount,
        LocalDateTime submittedAt,
        LocalDateTime approvedAt,
        LocalDateTime closedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        int lineItemCount,
        boolean editable,
        boolean deletable,
        int policyWarningCount,
        int policyUnjustifiedCount,
        /** The cash advance this report settles, or null for an ordinary report. */
        UUID cashAdvanceId,
        /** Start date of the trip or client meeting, or null if not yet entered. */
        LocalDate tripStartDate,
        /** Estimated trip end date entered by the employee, or null if not yet entered. */
        LocalDate tripEndDate,
        /** Last date the report may be submitted (trip end + configured days), or null without a trip end date. */
        LocalDate submissionDeadline,
        /** The configured {@code expense.submission.deadline-days}, so clients never hard-code the rule. */
        int submissionDeadlineDays,
        /** Actual reporting period start, or null if not entered. */
        LocalDate reportingPeriodStart,
        /** Actual reporting period end, or null if not entered. Never in the future. */
        LocalDate reportingPeriodEnd
) {

    /** Report without a cash advance link (the shape used before the link existed). */
    public ExpenseReportResponse(UUID reportId, String reportNumber, String employeeId, String title,
                                 String businessPurpose, String fiscalYear, UUID costCenterId, String costCenterName,
                                 String reportStatus, String paymentRoutingStatus, String invoiceHandoffStatus,
                                 UUID currencyId, String currencyCode, String baseCurrencyCode,
                                 BigDecimal totalAmount, BigDecimal reimbursableAmount,
                                 LocalDateTime submittedAt, LocalDateTime approvedAt, LocalDateTime closedAt,
                                 LocalDateTime createdAt, LocalDateTime updatedAt,
                                 int lineItemCount, boolean editable, boolean deletable,
                                 int policyWarningCount, int policyUnjustifiedCount) {
        this(reportId, reportNumber, employeeId, title, businessPurpose, fiscalYear, costCenterId, costCenterName,
                reportStatus, paymentRoutingStatus, invoiceHandoffStatus, currencyId, currencyCode, baseCurrencyCode,
                totalAmount, reimbursableAmount, submittedAt, approvedAt, closedAt, createdAt, updatedAt,
                lineItemCount, editable, deletable, policyWarningCount, policyUnjustifiedCount, null, null, null, null, 0,
                null, null);
    }
}
