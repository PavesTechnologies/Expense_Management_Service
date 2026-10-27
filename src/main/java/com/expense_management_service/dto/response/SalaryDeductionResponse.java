package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One salary deduction request as Finance sees it. The {@code can*} flags are computed on the server, so the screen
 * never offers an action the backend would refuse.
 */
public record SalaryDeductionResponse(
        UUID salaryDeductionRequestId,
        String payrollRequestId,
        UUID advanceId,
        String employeeId,
        BigDecimal requestedAmount,
        String payrollMonth,
        String status,
        boolean open,
        String emailStatus,
        LocalDateTime requestedAt,
        String requestedBy,
        LocalDateTime sentAt,
        boolean sentToPayroll,
        LocalDateTime cancelledAt,
        String cancelledBy,
        String cancellationReason,
        boolean outcomeRecorded,
        BigDecimal actualAmountDeducted,
        BigDecimal shortfall,
        String payrollMonthProcessed,
        String payrollReference,
        String evidenceType,
        boolean evidenceAttached,
        String evidenceNote,
        LocalDateTime confirmedAt,
        String confirmedBy,
        BigDecimal advanceOutstandingBalance,
        boolean canRetryEmail,
        boolean canCancel,
        boolean canRecordResult
) {
}
