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
        /** Approved with nothing to pay the employee (e.g. fully covered by a cash advance) - never "paid". */
        boolean noPaymentDue,
        /** What AP recorded when confirming the actual payment; null until then. */
        PaymentConfirmation paymentConfirmation
) {
    public record PaymentConfirmation(
            LocalDateTime confirmedAt,
            String confirmedBy,
            String paymentReference,
            String paymentMethod,
            LocalDate paymentDate,
            BigDecimal amountPaid,
            String remarks
    ) {
    }

    /** Shape before payment confirmation details were exposed. */
    public ExpenseReportResponse(UUID reportId, String reportNumber, String employeeId, String title,
                                 String businessPurpose, String fiscalYear, UUID costCenterId, String costCenterName,
                                 String reportStatus, String paymentRoutingStatus, String invoiceHandoffStatus,
                                 UUID currencyId, String currencyCode, String baseCurrencyCode,
                                 BigDecimal totalAmount, BigDecimal reimbursableAmount,
                                 LocalDateTime submittedAt, LocalDateTime approvedAt, LocalDateTime closedAt,
                                 LocalDateTime createdAt, LocalDateTime updatedAt,
                                 int lineItemCount, boolean editable, boolean deletable,
                                 int policyWarningCount, int policyUnjustifiedCount, UUID cashAdvanceId) {
        this(reportId, reportNumber, employeeId, title, businessPurpose, fiscalYear, costCenterId, costCenterName,
                reportStatus, paymentRoutingStatus, invoiceHandoffStatus, currencyId, currencyCode, baseCurrencyCode,
                totalAmount, reimbursableAmount, submittedAt, approvedAt, closedAt, createdAt, updatedAt,
                lineItemCount, editable, deletable, policyWarningCount, policyUnjustifiedCount, cashAdvanceId,
                false, null);
    }


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
                lineItemCount, editable, deletable, policyWarningCount, policyUnjustifiedCount, null);
    }
}
