package com.expense_management_service.dto.response;

import com.expense_management_service.entity.CashAdvanceRecoveryCase;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Flat view of a recovery case. The entity is not returned directly because its advance association is lazy. */
public record RecoveryCaseResponse(
        UUID recoveryCaseId,
        UUID advanceId,
        String employeeId,
        BigDecimal amountAtNotice,
        String status,
        LocalDateTime noticeSentAt,
        String noticeSentBy,
        LocalDateTime dueAt,
        LocalDateTime resolvedAt,
        LocalDateTime payrollReportSentAt
) {
    public static RecoveryCaseResponse from(CashAdvanceRecoveryCase c) {
        return new RecoveryCaseResponse(c.getRecoveryCaseId(), c.getAdvance().getAdvanceId(), c.getEmployeeId(),
                c.getAmountAtNotice(), c.getStatus(), c.getNoticeSentAt(), c.getNoticeSentBy(), c.getDueAt(),
                c.getResolvedAt(), c.getPayrollReportSentAt());
    }
}
