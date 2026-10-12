package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.CashAdvanceAdjustmentRequest;
import com.expense_management_service.dto.response.CashAdvanceAdjustmentResponse;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceAdjustment;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.repository.CashAdvanceAdjustmentRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.CashAdvanceAdjustmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Applies a finance-verified expense report against the cash advance it settles.
 *
 * <p>Rules:
 * <ul>
 *   <li>The report must be linked to an advance, and the advance named in the request (if any) must match.</li>
 *   <li>The report must be approved or closed, which means it has passed manager approval and Finance
 *       verification.</li>
 *   <li>A report can be offset once. A second offset is rejected rather than applied again.</li>
 *   <li>The offset may not exceed the report's verified total, nor the advance's outstanding balance.
 *       When no amount is given, the largest allowed amount is used.</li>
 *   <li>The amount is rounded to the currency's decimal places.</li>
 * </ul>
 *
 * <p>The write itself is delegated to {@link CashAdvanceAdjustmentService#create}, which also enforces
 * Finance-only access, recalculates the advance balance, closes the advance at zero, and recalculates the
 * report's reimbursable amount. Everything runs in one transaction, so a failure leaves nothing half-applied.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashAdvanceOffsetService {

    private final ExpenseReportRepository expenseReportRepository;
    private final CashAdvanceAdjustmentRepository cashAdvanceAdjustmentRepository;
    private final CashAdvanceAdjustmentService cashAdvanceAdjustmentService;
    private final CurrentUserService currentUserService;

    @Transactional
    public CashAdvanceAdjustmentResponse applyOffset(UUID reportId, UUID requestedAdvanceId, BigDecimal requestedAmount) {
        ExpenseReport report = expenseReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Expense report not found with id: " + reportId));

        CashAdvance advance = report.getCashAdvance();
        if (advance == null) {
            throw new BusinessRuleViolationException("This expense report is not linked to a cash advance.");
        }
        if (requestedAdvanceId != null && !requestedAdvanceId.equals(advance.getAdvanceId())) {
            throw new BusinessRuleViolationException(
                    "The expense report is linked to a different cash advance than the one given.");
        }

        if (report.getReportStatus() != ReportStatus.APPROVED && report.getReportStatus() != ReportStatus.CLOSED) {
            throw new BusinessRuleViolationException(
                    "Only an approved and Finance-verified expense report can be offset. Current status: "
                            + report.getReportStatus());
        }

        boolean alreadyOffset = cashAdvanceAdjustmentRepository.findByCashAdvance_AdvanceId(advance.getAdvanceId())
                .stream()
                .map(CashAdvanceAdjustment::getReport)
                .anyMatch(r -> r != null && reportId.equals(r.getReportId()));
        if (alreadyOffset) {
            throw new BusinessRuleViolationException(
                    "This expense report has already been offset against the cash advance. It cannot be applied twice.");
        }

        int decimals = report.getCurrency() != null ? report.getCurrency().getDecimalPlaces() : 2;
        BigDecimal verified = nz(report.getTotalAmount()).setScale(decimals, RoundingMode.HALF_UP);
        BigDecimal outstanding = (advance.getOutstandingBalance() != null
                ? advance.getOutstandingBalance()
                : advance.getAmount()).setScale(decimals, RoundingMode.HALF_UP);

        BigDecimal maximum = verified.min(outstanding);
        BigDecimal amount = requestedAmount != null
                ? requestedAmount.setScale(decimals, RoundingMode.HALF_UP)
                : maximum;

        if (amount.signum() <= 0) {
            throw new BusinessRuleViolationException("Offset amount must be greater than zero.");
        }
        if (amount.compareTo(verified) > 0) {
            throw new BusinessRuleViolationException(
                    "Offset amount " + amount + " exceeds the verified expense total of " + verified + ".");
        }
        if (amount.compareTo(outstanding) > 0) {
            throw new BusinessRuleViolationException(
                    "Offset amount " + amount + " exceeds the cash advance outstanding balance of " + outstanding + ".");
        }

        log.info("Applying offset {} of report {} against cash advance {}", amount, report.getReportNumber(),
                advance.getAdvanceId());

        return cashAdvanceAdjustmentService.create(new CashAdvanceAdjustmentRequest(
                advance.getAdvanceId(),
                reportId,
                amount,
                currentUserService.getEmployeeId()));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
