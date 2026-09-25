package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CashAdvanceSettlementResponse(
        UUID advanceId,
        String employeeId,
        BigDecimal totalAmount,
        String currencyCode,
        BigDecimal totalAdjustedAmount,
        BigDecimal totalRepaidAmount,
        BigDecimal outstandingBalance,
        String status,
        LocalDate settlementDueDate,
        BigDecimal repaymentAmount,
        BigDecimal reimbursementAmount,
        String settlementStatus,
        List<CashAdvanceAdjustmentResponse> adjustments,
        List<CashAdvanceRepaymentResponse> repayments
) {
    public CashAdvanceSettlementResponse(
            UUID advanceId,
            String employeeId,
            BigDecimal totalAmount,
            String currencyCode,
            BigDecimal totalAdjustedAmount,
            BigDecimal totalRepaidAmount,
            BigDecimal outstandingBalance,
            String status,
            LocalDate settlementDueDate,
            List<CashAdvanceAdjustmentResponse> adjustments,
            List<CashAdvanceRepaymentResponse> repayments
    ) {
        this(
                advanceId,
                employeeId,
                totalAmount,
                currencyCode,
                totalAdjustedAmount,
                totalRepaidAmount,
                outstandingBalance,
                status,
                settlementDueDate,
                outstandingBalance != null && outstandingBalance.compareTo(BigDecimal.ZERO) > 0 ? outstandingBalance : BigDecimal.ZERO,
                outstandingBalance != null && outstandingBalance.compareTo(BigDecimal.ZERO) < 0 ? outstandingBalance.abs() : BigDecimal.ZERO,
                outstandingBalance == null || outstandingBalance.compareTo(BigDecimal.ZERO) == 0 ? "FULLY_SETTLED" : (outstandingBalance.compareTo(BigDecimal.ZERO) > 0 ? "REPAYMENT_DUE" : "REIMBURSEMENT_DUE"),
                adjustments,
                repayments
        );
    }
}
