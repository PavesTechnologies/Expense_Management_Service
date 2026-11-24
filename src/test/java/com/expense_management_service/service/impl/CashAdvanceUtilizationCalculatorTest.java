package com.expense_management_service.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.expense_management_service.dto.response.CashAdvanceUtilizationResponse;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.repository.ApprovalLineItemReviewRepository;
import com.expense_management_service.repository.BudgetEncumbranceRepository;
import com.expense_management_service.repository.CashAdvanceAdjustmentRepository;
import com.expense_management_service.repository.CashAdvanceRepaymentRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.ReceiptRepository;
import com.expense_management_service.enums.ReportStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Missing-receipt count on a cash advance. A late receipt that Finance rejected is not proof of the expense, so it must
 * not clear the missing count for its line.
 */
class CashAdvanceUtilizationCalculatorTest {

    private ExpenseReportRepository reportRepo;
    private ExpenseLineItemRepository lineRepo;
    private ReceiptRepository receiptRepo;
    private CashAdvanceUtilizationCalculator calculator;
    private CashAdvance advance;
    private ExpenseReport report;
    private ExpenseLineItem line;

    @BeforeEach
    void setUp() {
        reportRepo = mock(ExpenseReportRepository.class);
        lineRepo = mock(ExpenseLineItemRepository.class);
        receiptRepo = mock(ReceiptRepository.class);
        calculator = new CashAdvanceUtilizationCalculator(
                reportRepo,
                lineRepo,
                receiptRepo,
                mock(ApprovalLineItemReviewRepository.class),
                mock(BudgetEncumbranceRepository.class),
                mock(CashAdvanceAdjustmentRepository.class),
                mock(CashAdvanceRepaymentRepository.class));

        UUID advanceId = UUID.randomUUID();
        advance = mock(CashAdvance.class);
        when(advance.getAdvanceId()).thenReturn(advanceId);
        when(advance.getAmount()).thenReturn(new BigDecimal("1000.00"));

        report = new ExpenseReport();
        report.setReportId(UUID.randomUUID());
        report.setReportNumber("EXP-TEST-1");
        report.setReportStatus(ReportStatus.APPROVED);
        when(reportRepo.findByCashAdvance_AdvanceIdOrderByCreatedAtAsc(advanceId)).thenReturn(List.of(report));

        ExpenseCategory meals = mock(ExpenseCategory.class);
        when(meals.getReceiptRequired()).thenReturn(Boolean.TRUE);
        when(meals.getCategoryId()).thenReturn(UUID.randomUUID());
        when(meals.getCategoryName()).thenReturn("Meals");

        line = new ExpenseLineItem();
        line.setLineItemId(UUID.randomUUID());
        line.setReport(report);
        line.setCategory(meals);
        line.setAmount(new BigDecimal("250.00"));
        line.setBaseAmount(new BigDecimal("250.00"));
        line.setExpenseDate(LocalDate.of(2026, 11, 2));
        when(lineRepo.findByReport_ReportId(report.getReportId())).thenReturn(List.of(line));
    }

    @Test
    void reportInApproval_isHeldAgainstTheAdvance_onlyTheRestIsSettleable_andNoReimbursementIsOwedYet() {
        report.setReportStatus(ReportStatus.PENDING_APPROVAL);
        report.setReimbursableAmount(new BigDecimal("250.00")); // still the gross claim before approval
        when(receiptRepo.findByReport_ReportId(report.getReportId())).thenReturn(List.of());

        CashAdvanceUtilizationResponse response = calculator.calculate(advance);

        assertThat(response.inApprovalAmount()).isEqualByComparingTo("250.00");
        assertThat(response.settleableAmount()).isEqualByComparingTo("750.00");
        assertThat(response.reimbursementOwedAmount()).isEqualByComparingTo("0");
        assertThat(response.closureStatus()).isEqualTo("REPAYMENT_DUE");
    }

    @Test
    void approvedReport_isNotHeld() {
        when(receiptRepo.findByReport_ReportId(report.getReportId())).thenReturn(List.of());

        CashAdvanceUtilizationResponse response = calculator.calculate(advance);

        assertThat(response.inApprovalAmount()).isEqualByComparingTo("0");
        // Nothing is set against the advance in this test (no adjustments), so the whole 1,000 can be settled.
        assertThat(response.settleableAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    void lineWithoutAnyReceiptIsCountedAsMissing() {
        when(receiptRepo.findByReport_ReportId(report.getReportId())).thenReturn(List.of());

        assertThat(missingCount()).isEqualTo(1);
    }

    @Test
    void verifiedLateReceiptClearsTheMissingCount() {
        when(receiptRepo.findByReport_ReportId(report.getReportId())).thenReturn(List.of(
                lateReceipt(Receipt.VERIFICATION_VERIFIED)));

        assertThat(missingCount()).isZero();
    }

    @Test
    void lateReceiptStillUnderReviewClearsTheMissingCount() {
        when(receiptRepo.findByReport_ReportId(report.getReportId())).thenReturn(List.of(
                lateReceipt(Receipt.VERIFICATION_PENDING)));

        assertThat(missingCount()).isZero();
    }

    @Test
    void rejectedLateReceiptDoesNotClearTheMissingCount() {
        when(receiptRepo.findByReport_ReportId(report.getReportId())).thenReturn(List.of(
                lateReceipt(Receipt.VERIFICATION_REJECTED)));

        CashAdvanceUtilizationResponse response = calculator.calculate(advance);

        assertThat(missingCount(response)).isEqualTo(1);
        // The rejected file is not linked to the line as proof either.
        assertThat(response.categories().get(0).lines().get(0).receipts()).isEmpty();
    }

    private int missingCount() {
        return missingCount(calculator.calculate(advance));
    }

    private int missingCount(CashAdvanceUtilizationResponse response) {
        return response.categories().stream().mapToInt(c -> c.missingReceiptCount()).sum();
    }

    private Receipt lateReceipt(String verificationStatus) {
        return Receipt.builder()
                .report(report)
                .lineItem(line)
                .employeeId(report.getEmployeeId())
                .originalFileName("late.pdf")
                .storedFileName("late.pdf")
                .objectKey("receipts/late.pdf")
                .contentType("application/pdf")
                .fileSize(10)
                .uploadedBy("5100014")
                .attachmentKind(Receipt.ATTACHMENT_LATE)
                .verificationStatus(verificationStatus)
                .build();
    }
}
