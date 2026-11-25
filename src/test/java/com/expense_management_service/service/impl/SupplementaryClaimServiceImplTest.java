package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.dto.request.SupplementaryClaimCreateRequest;
import com.expense_management_service.dto.request.SupplementaryClaimItemRequest;
import com.expense_management_service.dto.response.SupplementaryClaimResponse;
import com.expense_management_service.dto.response.SupplementaryEligibilityResponse;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceCarryForward;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.SalaryDeductionRequest;
import com.expense_management_service.entity.SupplementaryClaimHistory;
import com.expense_management_service.entity.SupplementaryClaimItem;
import com.expense_management_service.entity.SupplementaryExpenseClaim;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.CashAdvanceCarryForwardRepository;
import com.expense_management_service.repository.CashAdvanceRecoveryCaseRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.SalaryDeductionRequestRepository;
import com.expense_management_service.repository.ReceiptRepository;
import com.expense_management_service.repository.SupplementaryClaimHistoryRepository;
import com.expense_management_service.service.SupplementaryPaymentService;
import com.expense_management_service.service.SupplementaryReceiptService;
import com.expense_management_service.repository.SupplementaryExpenseClaimRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.PermissionConstants;
import com.expense_management_service.security.RoleConstants;
import com.expense_management_service.service.NotificationService;
import com.expense_management_service.service.SupplementaryClaimService.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupplementaryClaimServiceImplTest {

    @Mock SupplementaryExpenseClaimRepository claimRepository;
    @Mock SupplementaryClaimHistoryRepository historyRepository;
    @Mock ExpenseReportRepository reportRepository;
    @Mock ExpenseCategoryRepository categoryRepository;
    @Mock CashAdvanceRecoveryCaseRepository recoveryRepository;
    @Mock CashAdvanceCarryForwardRepository carryForwardRepository;
    @Mock SalaryDeductionRequestRepository salaryDeductionRepository;
    @Mock EmployeeCacheRepository employeeCacheRepository;
    @Mock AuditLogRepository auditLogRepository;
    @Mock NotificationService notificationService;
    @Mock CurrentUserService currentUserService;
    @Mock ReceiptRepository receiptRepository;
    @Mock SupplementaryPaymentService paymentService;
    @Mock SupplementaryReceiptService receiptService;
    @Mock com.expense_management_service.repository.CashAdvanceAdjustmentRepository cashAdvanceAdjustmentRepository;
    @Mock com.expense_management_service.repository.CashAdvanceRepository cashAdvanceRepository;
    @Mock CashAdvanceBalanceCalculator balanceCalculator;
    @Mock CashAdvanceBalanceLedger cashAdvanceBalanceLedger;

    @InjectMocks SupplementaryClaimServiceImpl service;

    private static final String EMPLOYEE = "EMP1";
    private static final String MANAGER = "MGR1";
    private static final String FINANCE = "FIN1";

    // ---------------------------------------------------------------- fixtures

    private ExpenseReport settledReport(ReportStatus status, BigDecimal owed) {
        Currency inr = Currency.builder().currencyCode("INR").build();
        return ExpenseReport.builder()
                .reportId(UUID.randomUUID())
                .reportNumber("RPT-1")
                .employeeId(EMPLOYEE)
                .reportStatus(status)
                .currency(inr)
                .totalAmount(owed)
                .reimbursableAmount(owed)
                .paymentRoutingStatus(PaymentRoutingStatus.PAYMENT_COMPLETED)
                .paymentCompletedAt(LocalDateTime.now().minusDays(3))
                .paidAmount(owed)
                .tripStartDate(LocalDate.now().minusDays(30))
                .tripEndDate(LocalDate.now().minusDays(20))
                .build();
    }

    private SupplementaryExpenseClaim claim(String status, ExpenseReport report) {
        return SupplementaryExpenseClaim.builder()
                .claimId(UUID.randomUUID())
                .claimNumber("SUP-TEST")
                .original(report)
                .employeeId(EMPLOYEE)
                .createdBy(EMPLOYEE)
                .createdOnBehalf(false)
                .lateReasonCode("RECEIPT_RECEIVED_LATE")
                .totalAmount(new BigDecimal("50.0000"))
                .currencyCode("INR")
                .status(status)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .items(new ArrayList<>(List.of(item())))
                .build();
    }

    private SupplementaryClaimItem item() {
        return SupplementaryClaimItem.builder()
                .claimItemId(UUID.randomUUID())
                .expenseDate(LocalDate.now().minusDays(25))
                .categoryId(UUID.randomUUID())
                .amount(new BigDecimal("50.0000"))
                .currencyCode("INR")
                .build();
    }

    private SupplementaryClaimItemRequest validItem(LocalDate date) {
        return new SupplementaryClaimItemRequest(date, UUID.randomUUID(), "Shop", "Taxi", new BigDecimal("50"), null, null);
    }

    private void actAs(String employeeId, String... permissions) {
        when(currentUserService.getEmployeeId()).thenReturn(employeeId);
        when(currentUserService.getPermissions()).thenReturn(List.of(permissions));
    }

    private void actAsFinance() {
        when(currentUserService.getEmployeeId()).thenReturn(FINANCE);
        when(currentUserService.getActorId()).thenReturn(FINANCE);
        when(currentUserService.getCurrentUser()).thenReturn(new CurrentUser(null, null, FINANCE, null, null,
                List.of(RoleConstants.FINANCE_EXECUTIVE), List.of()));
        when(currentUserService.getPermissions()).thenReturn(List.of());
    }

    private void managerRelation() {
        EmployeeCache cache = new EmployeeCache();
        cache.setEmployeeId(EMPLOYEE);
        cache.setManagerEmployeeId(MANAGER);
        when(employeeCacheRepository.findByEmployeeId(EMPLOYEE)).thenReturn(Optional.of(cache));
    }

    // ---------------------------------------------------------------- eligibility

    @Test
    void reportNotYetApprovedIsNotEligible() {
        ExpenseReport report = settledReport(ReportStatus.PENDING_APPROVAL, new BigDecimal("100"));
        when(reportRepository.findById(report.getReportId())).thenReturn(Optional.of(report));

        SupplementaryEligibilityResponse result = service.checkEligibility(report.getReportId());

        assertFalse(result.eligible());
        assertTrue(result.reason().contains("approved and paid"));
    }

    @Test
    void settledReportWithCompletedApPaymentIsEligible() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        when(reportRepository.findById(report.getReportId())).thenReturn(Optional.of(report));

        assertTrue(service.checkEligibility(report.getReportId()).eligible());
    }

    @Test
    void settledReportWithoutApPaymentIsNotEligible() {
        ExpenseReport report = settledReport(ReportStatus.CLOSED, new BigDecimal("100"));
        report.setPaymentRoutingStatus(PaymentRoutingStatus.NONE);
        when(reportRepository.findById(report.getReportId())).thenReturn(Optional.of(report));

        assertFalse(service.checkEligibility(report.getReportId()).eligible());
    }

    @Test
    void openSalaryDeductionBlocksEligibilityEvenWithAdvanceClosed() {
        ExpenseReport report = settledReport(ReportStatus.CLOSED, BigDecimal.ZERO);
        CashAdvance advance = CashAdvance.builder().advanceId(UUID.randomUUID()).status("CLOSED")
                .settledAt(LocalDateTime.now().minusDays(2)).build();
        report.setCashAdvance(advance);
        SalaryDeductionRequest failed = mock(SalaryDeductionRequest.class);
        when(failed.isOpen()).thenReturn(false);
        when(failed.getStatus()).thenReturn(SalaryDeductionRequest.PAYROLL_FAILED);
        when(salaryDeductionRepository.findByAdvance_AdvanceIdAndStatusIn(eq(advance.getAdvanceId()), anyCollection()))
                .thenReturn(List.of(failed));
        when(reportRepository.findById(report.getReportId())).thenReturn(Optional.of(report));

        SupplementaryEligibilityResponse result = service.checkEligibility(report.getReportId());

        assertFalse(result.eligible(), "a failed salary deduction must keep the advance open");
    }

    @Test
    void proposedCarryForwardBlocksEligibility() {
        ExpenseReport report = settledReport(ReportStatus.CLOSED, BigDecimal.ZERO);
        CashAdvance advance = CashAdvance.builder().advanceId(UUID.randomUUID()).status("CLOSED")
                .settledAt(LocalDateTime.now().minusDays(2)).build();
        report.setCashAdvance(advance);
        when(carryForwardRepository.findBySourceAdvance_AdvanceIdAndStatusIn(eq(advance.getAdvanceId()), anyCollection()))
                .thenReturn(List.of(mock(CashAdvanceCarryForward.class)));
        when(reportRepository.findById(report.getReportId())).thenReturn(Optional.of(report));

        assertFalse(service.checkEligibility(report.getReportId()).eligible());
    }

    @Test
    void fullyCoveredReportWithReconciledAdvanceIsEligibleWithoutApPayment() {
        ExpenseReport report = settledReport(ReportStatus.CLOSED, BigDecimal.ZERO);
        report.setPaymentRoutingStatus(PaymentRoutingStatus.NONE);
        CashAdvance advance = CashAdvance.builder().advanceId(UUID.randomUUID()).status("CLOSED")
                .settledAt(LocalDateTime.now().minusDays(2)).build();
        report.setCashAdvance(advance);
        when(reportRepository.findById(report.getReportId())).thenReturn(Optional.of(report));

        assertTrue(service.checkEligibility(report.getReportId()).eligible());
    }

    // ---------------------------------------------------------------- create

    @Test
    void employeeCannotRaiseClaimOnSomeoneElsesReport() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        report.setEmployeeId("EMP2");
        actAs(EMPLOYEE);
        when(reportRepository.findById(report.getReportId())).thenReturn(Optional.of(report));
        SupplementaryClaimCreateRequest request = new SupplementaryClaimCreateRequest(
                report.getReportId(), "RECEIPT_RECEIVED_LATE", null, null, null, null);

        assertThrows(AccessDeniedException.class, () -> service.createForSelf(request));
    }

    @Test
    void expenseDateOutsideOriginalPeriodIsRejected() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        actAs(EMPLOYEE);
        when(reportRepository.findById(report.getReportId())).thenReturn(Optional.of(report));
        SupplementaryClaimCreateRequest request = new SupplementaryClaimCreateRequest(
                report.getReportId(), "RECEIPT_RECEIVED_LATE", null, null, null,
                List.of(validItem(LocalDate.now().minusDays(90))));

        assertThrows(BusinessRuleViolationException.class, () -> service.createForSelf(request));
    }

    @Test
    void financeCreationWithoutPermissionFailsClosed() {
        actAs(FINANCE);
        SupplementaryClaimCreateRequest request = new SupplementaryClaimCreateRequest(
                UUID.randomUUID(), "RECEIPT_RECEIVED_LATE", null, "Missed by employee", EMPLOYEE, null);

        assertThrows(AccessDeniedException.class, () -> service.createOnBehalf(request));
    }

    // ---------------------------------------------------------------- acknowledgment and submission

    @Test
    void financeCreatedClaimCannotBeSubmittedBeforeEmployeeAcknowledges() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        SupplementaryExpenseClaim claim = claim(SupplementaryExpenseClaim.DRAFT, report);
        claim.setCreatedOnBehalf(true);
        claim.setCreatedBy(FINANCE);
        actAs(EMPLOYEE);
        when(claimRepository.findByIdForUpdate(claim.getClaimId())).thenReturn(Optional.of(claim));

        assertThrows(BusinessRuleViolationException.class, () -> service.submit(claim.getClaimId()));
    }

    @Test
    void claimWithNoItemsCannotBeSubmitted() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        SupplementaryExpenseClaim claim = claim(SupplementaryExpenseClaim.DRAFT, report);
        claim.getItems().clear();
        actAs(EMPLOYEE);
        when(claimRepository.findByIdForUpdate(claim.getClaimId())).thenReturn(Optional.of(claim));

        assertThrows(BusinessRuleViolationException.class, () -> service.submit(claim.getClaimId()));
    }

    // ---------------------------------------------------------------- manager review

    @Test
    void managerCannotReviewClaimHeCreated() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        SupplementaryExpenseClaim claim = claim(SupplementaryExpenseClaim.PENDING_MANAGER, report);
        claim.setCreatedBy(MANAGER);
        actAs(MANAGER);
        managerRelation();
        when(claimRepository.findByIdForUpdate(claim.getClaimId())).thenReturn(Optional.of(claim));

        assertThrows(AccessDeniedException.class,
                () -> service.managerReview(claim.getClaimId(), Decision.APPROVE, null));
    }

    @Test
    void userWhoIsNotTheEmployeesManagerCannotReview() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        SupplementaryExpenseClaim claim = claim(SupplementaryExpenseClaim.PENDING_MANAGER, report);
        actAs("SOMEONE_ELSE");
        managerRelation();
        when(claimRepository.findByIdForUpdate(claim.getClaimId())).thenReturn(Optional.of(claim));

        assertThrows(AccessDeniedException.class,
                () -> service.managerReview(claim.getClaimId(), Decision.APPROVE, null));
    }

    @Test
    void managerApprovalMovesClaimToFinance() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        SupplementaryExpenseClaim claim = claim(SupplementaryExpenseClaim.PENDING_MANAGER, report);
        actAs(MANAGER);
        managerRelation();
        when(claimRepository.findByIdForUpdate(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any(SupplementaryExpenseClaim.class))).thenAnswer(i -> i.getArgument(0));

        SupplementaryClaimResponse response = service.managerReview(claim.getClaimId(), Decision.APPROVE, null);

        assertEquals(SupplementaryExpenseClaim.PENDING_FINANCE, response.status());
        verify(notificationService).notifyRole(eq(NotificationService.ROLE_FINANCE), any());
    }

    @Test
    void rejectionNeedsAReason() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        SupplementaryExpenseClaim claim = claim(SupplementaryExpenseClaim.PENDING_MANAGER, report);
        actAs(MANAGER);
        managerRelation();
        when(claimRepository.findByIdForUpdate(claim.getClaimId())).thenReturn(Optional.of(claim));

        assertThrows(BusinessRuleViolationException.class,
                () -> service.managerReview(claim.getClaimId(), Decision.REJECT, "  "));
    }

    // ---------------------------------------------------------------- Finance review

    @Test
    void financeReviewWithoutFinanceRoleFailsClosed() {
        actAs(FINANCE);

        assertThrows(AccessDeniedException.class,
                () -> service.financeReview(UUID.randomUUID(), Decision.APPROVE, null));
    }

    @Test
    void financeCreatorCannotApproveOwnClaim() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        SupplementaryExpenseClaim claim = claim(SupplementaryExpenseClaim.PENDING_FINANCE, report);
        claim.setCreatedBy(FINANCE);
        actAsFinance();
        when(claimRepository.findByIdForUpdate(claim.getClaimId())).thenReturn(Optional.of(claim));

        assertThrows(AccessDeniedException.class,
                () -> service.financeReview(claim.getClaimId(), Decision.APPROVE, null));
    }

    @Test
    void clarificationFromFinanceReturnsToFinanceOnResubmit() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        SupplementaryExpenseClaim claim = claim(SupplementaryExpenseClaim.CLARIFICATION_REQUIRED, report);
        actAs(EMPLOYEE);
        when(claimRepository.findByIdForUpdate(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any(SupplementaryExpenseClaim.class))).thenAnswer(i -> i.getArgument(0));
        SupplementaryClaimHistory asked = SupplementaryClaimHistory.builder()
                .action("CLARIFICATION_REQUESTED")
                .previousStatus(SupplementaryExpenseClaim.PENDING_FINANCE)
                .newStatus(SupplementaryExpenseClaim.CLARIFICATION_REQUIRED)
                .createdAt(LocalDateTime.now())
                .build();
        when(historyRepository.findByClaim_ClaimIdOrderByCreatedAtAsc(claim.getClaimId())).thenReturn(List.of(asked));
        // Resubmission needs a receipt on every expense, the same rule as the first submission.
        when(receiptRepository.findBySupplementaryClaimItem_ClaimItemId(any())).thenReturn(List.of(new com.expense_management_service.entity.Receipt()));

        SupplementaryClaimResponse response = service.resubmit(claim.getClaimId());

        assertEquals(SupplementaryExpenseClaim.PENDING_FINANCE, response.status());
    }

    @Test
    void employeeCannotSubmitAnotherEmployeesClaim() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        SupplementaryExpenseClaim claim = claim(SupplementaryExpenseClaim.DRAFT, report);
        actAs("EMP2");
        when(claimRepository.findByIdForUpdate(claim.getClaimId())).thenReturn(Optional.of(claim));

        assertThrows(AccessDeniedException.class, () -> service.submit(claim.getClaimId()));
    }

    @Test
    void itemDateCheckUsesUnionOfTripAndReportingPeriod() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        report.setTripStartDate(null);
        report.setTripEndDate(null);
        report.setReportingPeriodStart(LocalDate.now().minusDays(40));
        report.setReportingPeriodEnd(LocalDate.now().minusDays(35));
        actAs(EMPLOYEE);
        when(reportRepository.findById(report.getReportId())).thenReturn(Optional.of(report));
        SupplementaryClaimCreateRequest inside = new SupplementaryClaimCreateRequest(
                report.getReportId(), "RECEIPT_RECEIVED_LATE", null, null, null,
                List.of(validItem(LocalDate.now().minusDays(38))));
        SupplementaryClaimCreateRequest outside = new SupplementaryClaimCreateRequest(
                report.getReportId(), "RECEIPT_RECEIVED_LATE", null, null, null,
                List.of(validItem(LocalDate.now().minusDays(10))));

        when(categoryRepository.existsById(any())).thenReturn(true);
        when(claimRepository.save(any(SupplementaryExpenseClaim.class))).thenAnswer(i -> i.getArgument(0));

        assertThrows(BusinessRuleViolationException.class, () -> service.createForSelf(outside));
        SupplementaryClaimResponse created = service.createForSelf(inside);
        assertEquals(SupplementaryExpenseClaim.DRAFT, created.status());
        assertEquals(1, created.items().size());
    }

    @Test
    void paidReportStillShowingApprovedIsEligible() {
        // Payment changes paymentRoutingStatus, not reportStatus, so an APPROVED report can be paid in full.
        ExpenseReport report = settledReport(ReportStatus.APPROVED, new BigDecimal("100"));
        when(reportRepository.findById(report.getReportId())).thenReturn(Optional.of(report));

        assertTrue(service.checkEligibility(report.getReportId()).eligible());
    }

    @Test
    void approvedReportAwaitingPaymentIsNotEligible() {
        ExpenseReport report = settledReport(ReportStatus.APPROVED, new BigDecimal("100"));
        report.setPaymentRoutingStatus(PaymentRoutingStatus.APPROVED_FOR_PAYMENT);
        report.setPaymentCompletedAt(null);
        when(reportRepository.findById(report.getReportId())).thenReturn(Optional.of(report));

        assertFalse(service.checkEligibility(report.getReportId()).eligible());
    }

    @Test
    void submitIsRefusedWhenAnExpenseHasNoReceipt() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        SupplementaryExpenseClaim claim = claim(SupplementaryExpenseClaim.DRAFT, report);
        actAs(EMPLOYEE);
        when(claimRepository.findByIdForUpdate(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(receiptRepository.findBySupplementaryClaimItem_ClaimItemId(any())).thenReturn(List.of());

        assertThrows(BusinessRuleViolationException.class, () -> service.submit(claim.getClaimId()));
    }

    @Test
    void approvalByFinanceRequestsTheApPaymentOnce() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        SupplementaryExpenseClaim claim = claim(SupplementaryExpenseClaim.PENDING_FINANCE, report);
        actAsFinance();
        when(claimRepository.findByIdForUpdate(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any(SupplementaryExpenseClaim.class))).thenAnswer(i -> i.getArgument(0));

        SupplementaryClaimResponse response = service.financeReview(claim.getClaimId(), Decision.APPROVE, null);

        assertEquals(SupplementaryExpenseClaim.PAYMENT_PENDING, response.status());
        verify(paymentService).requestPayment(any(SupplementaryExpenseClaim.class));
    }

    @Test
    void ownerSeesEveryClaimRaisedAgainstTheirReport() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        SupplementaryExpenseClaim first = claim(SupplementaryExpenseClaim.SETTLED, report);
        SupplementaryExpenseClaim second = claim(SupplementaryExpenseClaim.DRAFT, report);
        actAs(EMPLOYEE);
        when(reportRepository.findById(report.getReportId())).thenReturn(Optional.of(report));
        when(claimRepository.findByOriginal_ReportIdAndStatusIn(eq(report.getReportId()), anyCollection()))
                .thenReturn(List.of(first, second));

        assertEquals(2, service.listForReport(report.getReportId()).size());
    }

    @Test
    void anotherEmployeeCannotListClaimsOfSomeoneElsesReport() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        actAs("SOMEONE_ELSE");
        when(reportRepository.findById(report.getReportId())).thenReturn(Optional.of(report));

        assertThrows(AccessDeniedException.class, () -> service.listForReport(report.getReportId()));
    }

    @Test
    void automaticPath_createsTheExpenseAndAttachesTheReceiptToIt() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        SupplementaryExpenseClaim claim = claim(SupplementaryExpenseClaim.DRAFT, report);
        actAs(EMPLOYEE);
        when(claimRepository.findById(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any(SupplementaryExpenseClaim.class))).thenAnswer(i -> i.getArgument(0));
        when(categoryRepository.existsById(any())).thenReturn(true);
        var receipt = new com.expense_management_service.entity.Receipt();
        receipt.setReceiptId(UUID.randomUUID());
        receipt.setEmployeeId(EMPLOYEE);
        receipt.setAttachmentKind(com.expense_management_service.entity.Receipt.ATTACHMENT_SUPPLEMENTARY);
        when(receiptRepository.findByIdForUpdate(receipt.getReceiptId())).thenReturn(Optional.of(receipt));

        service.addItemFromReceipt(claim.getClaimId(), receipt.getReceiptId(), validItem(LocalDate.now().minusDays(25)));

        assertTrue(receipt.getSupplementaryClaimItem() != null);
        assertEquals(2, claim.getItems().size());
    }

    @Test
    void automaticPath_refusesAReceiptThatIsAlreadyAttached() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        SupplementaryExpenseClaim claim = claim(SupplementaryExpenseClaim.DRAFT, report);
        actAs(EMPLOYEE);
        when(claimRepository.findById(claim.getClaimId())).thenReturn(Optional.of(claim));
        var receipt = new com.expense_management_service.entity.Receipt();
        receipt.setReceiptId(UUID.randomUUID());
        receipt.setEmployeeId(EMPLOYEE);
        receipt.setAttachmentKind(com.expense_management_service.entity.Receipt.ATTACHMENT_SUPPLEMENTARY);
        receipt.setSupplementaryClaimItem(item());
        when(receiptRepository.findByIdForUpdate(receipt.getReceiptId())).thenReturn(Optional.of(receipt));

        assertThrows(BusinessRuleViolationException.class, () ->
                service.addItemFromReceipt(claim.getClaimId(), receipt.getReceiptId(), validItem(LocalDate.now().minusDays(25))));
    }

    // ---------------------------------------------------------------- edit and delete of an expense

    @Test
    void updateItem_changesTheExpenseAndLeavesItsReceiptsAlone() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        SupplementaryExpenseClaim claim = claim(SupplementaryExpenseClaim.DRAFT, report);
        SupplementaryClaimItem existing = claim.getItems().get(0);
        actAs(EMPLOYEE);
        when(claimRepository.findById(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(categoryRepository.existsById(any())).thenReturn(true);
        var request = new SupplementaryClaimItemRequest(LocalDate.now().minusDays(25), UUID.randomUUID(), "Shop", "Taxi",
                new BigDecimal("70"), null, null);

        service.updateItem(claim.getClaimId(), existing.getClaimItemId(), request);

        assertEquals(0, new BigDecimal("70").compareTo(existing.getAmount()));
        verify(receiptService, never()).deleteAllForItem(any());
        verify(receiptService, never()).detachAllForClaim(any());
        verify(receiptRepository, never()).deleteAll(anyCollection());
        verify(receiptRepository, never()).delete(any());
    }

    @Test
    void removeItem_deletesTheExpenseTogetherWithItsReceipts() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        SupplementaryExpenseClaim claim = claim(SupplementaryExpenseClaim.DRAFT, report);
        SupplementaryClaimItem existing = claim.getItems().get(0);
        actAs(EMPLOYEE);
        when(claimRepository.findById(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.removeItem(claim.getClaimId(), existing.getClaimItemId());

        verify(receiptService).deleteAllForItem(existing.getClaimItemId());
        assertTrue(claim.getItems().isEmpty());
    }

    @Test
    void removeItem_refusesAnExpenseNotOnThisClaim() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        SupplementaryExpenseClaim claim = claim(SupplementaryExpenseClaim.DRAFT, report);
        actAs(EMPLOYEE);
        when(claimRepository.findById(claim.getClaimId())).thenReturn(Optional.of(claim));

        assertThrows(com.expense_management_service.common.exception.ResourceNotFoundException.class,
                () -> service.removeItem(claim.getClaimId(), UUID.randomUUID()));
        verify(receiptService, never()).deleteAllForItem(any());
        assertEquals(1, claim.getItems().size());
    }

    @Test
    void editAndDelete_refusedForAnotherEmployee() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        SupplementaryExpenseClaim claim = claim(SupplementaryExpenseClaim.DRAFT, report);
        UUID itemId = claim.getItems().get(0).getClaimItemId();
        actAs("SOMEONE_ELSE");
        when(claimRepository.findById(claim.getClaimId())).thenReturn(Optional.of(claim));

        assertThrows(AccessDeniedException.class, () -> service.removeItem(claim.getClaimId(), itemId));
        assertThrows(AccessDeniedException.class, () -> service.updateItem(claim.getClaimId(), itemId,
                validItem(LocalDate.now().minusDays(25))));
        verify(receiptService, never()).deleteAllForItem(any());
        assertEquals(1, claim.getItems().size());
    }

    /** Only a Draft or a claim that needs clarification can change; every other status is refused for edit and delete. */
    @ParameterizedTest
    @ValueSource(strings = {
            "PENDING_MANAGER", "PENDING_FINANCE", "APPROVED", "PAYMENT_PENDING",
            "SETTLED", "MANAGER_REJECTED", "FINANCE_REJECTED", "CANCELLED"})
    void editAndDelete_refusedOnceTheClaimIsNoLongerDraftOrClarification(String status) {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        SupplementaryExpenseClaim claim = claim(status, report);
        UUID itemId = claim.getItems().get(0).getClaimItemId();
        actAs(EMPLOYEE);
        when(claimRepository.findById(claim.getClaimId())).thenReturn(Optional.of(claim));

        assertThrows(BusinessRuleViolationException.class, () -> service.removeItem(claim.getClaimId(), itemId));
        assertThrows(BusinessRuleViolationException.class, () -> service.updateItem(claim.getClaimId(), itemId,
                validItem(LocalDate.now().minusDays(25))));
        verify(receiptService, never()).deleteAllForItem(any());
        assertEquals(1, claim.getItems().size());
    }

    @Test
    void editAndDelete_allowedWhileClarificationIsRequired() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        SupplementaryExpenseClaim claim = claim(SupplementaryExpenseClaim.CLARIFICATION_REQUIRED, report);
        UUID itemId = claim.getItems().get(0).getClaimItemId();
        actAs(EMPLOYEE);
        when(claimRepository.findById(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(categoryRepository.existsById(any())).thenReturn(true);

        service.updateItem(claim.getClaimId(), itemId, validItem(LocalDate.now().minusDays(25)));
        service.removeItem(claim.getClaimId(), itemId);

        verify(receiptService).deleteAllForItem(itemId);
    }

    @Test
    void financeUserWithoutEmployeeIdCanApproveUsingTheirEmailAsActor() {
        ExpenseReport report = settledReport(ReportStatus.REIMBURSED, new BigDecimal("100"));
        SupplementaryExpenseClaim claim = claim(SupplementaryExpenseClaim.PENDING_FINANCE, report);
        when(currentUserService.getEmployeeId()).thenReturn(null);
        when(currentUserService.getActorId()).thenReturn("finance.user@example.com");
        when(currentUserService.getCurrentUser()).thenReturn(new CurrentUser(null, null, null, "finance.user@example.com", null,
                List.of(RoleConstants.FINANCE_EXECUTIVE), List.of()));
        when(claimRepository.findByIdForUpdate(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any(SupplementaryExpenseClaim.class))).thenAnswer(i -> i.getArgument(0));

        SupplementaryClaimResponse response = service.financeReview(claim.getClaimId(), Decision.APPROVE, null);

        assertEquals(SupplementaryExpenseClaim.PAYMENT_PENDING, response.status());
    }

    // ---------------------------------------------------------------- late bill after a carry-forward

    /** Advance 1 (5,000; 3,500 spent) closed after carrying its 1,500 balance into Advance 2. */
    private record Carried(ExpenseReport report, com.expense_management_service.entity.CashAdvance source,
                           com.expense_management_service.entity.CashAdvance destination) {
    }

    private Carried carriedForward(String destinationStatus, String destinationOwes) {
        var source = com.expense_management_service.entity.CashAdvance.builder()
                .advanceId(UUID.randomUUID()).title("Advance 1").status("CLOSED").settledAt(LocalDateTime.now().minusDays(5)).build();
        var destination = com.expense_management_service.entity.CashAdvance.builder()
                .advanceId(UUID.randomUUID()).title("Advance 2").status(destinationStatus).build();
        ExpenseReport report = settledReport(ReportStatus.APPROVED, BigDecimal.ZERO);
        report.setCashAdvance(source);
        report.setPaymentRoutingStatus(null);
        var carry = com.expense_management_service.entity.CashAdvanceCarryForward.builder()
                .carryForwardId(UUID.randomUUID()).sourceAdvance(source).destinationAdvance(destination)
                .amount(new BigDecimal("1500")).status(com.expense_management_service.entity.CashAdvanceCarryForward.APPLIED)
                .appliedAt(LocalDateTime.now().minusDays(5)).build();
        // Like the database: the applied carry-forward comes back only when applied ones are asked for.
        when(carryForwardRepository.findBySourceAdvance_AdvanceIdAndStatusIn(eq(source.getAdvanceId()), anyCollection()))
                .thenAnswer(i -> ((java.util.Collection<?>) i.getArgument(1)).contains(com.expense_management_service.entity.CashAdvanceCarryForward.APPLIED)
                        ? List.of(carry) : List.of());
        when(balanceCalculator.rawOutstandingFor(any())).thenReturn(java.util.Map.of(destination.getAdvanceId(), new BigDecimal(destinationOwes)));
        return new Carried(report, source, destination);
    }

    private SupplementaryExpenseClaim lateBill(ExpenseReport report, String amount) {
        SupplementaryExpenseClaim claim = claim(SupplementaryExpenseClaim.PENDING_FINANCE, report);
        claim.setTotalAmount(new BigDecimal(amount));
        actAsFinance();
        when(claimRepository.findByIdForUpdate(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any(SupplementaryExpenseClaim.class))).thenAnswer(i -> i.getArgument(0));
        when(cashAdvanceAdjustmentRepository.findByReport_ReportId(report.getReportId())).thenReturn(List.of());
        return claim;
    }

    @Test
    void lateBillAfterCarryForward_isSetAgainstTheNextAdvance_andSettledWithoutAnApPayment() {
        Carried c = carriedForward("IN_PROGRESS", "1500");
        SupplementaryExpenseClaim claim = lateBill(c.report(), "1000");

        SupplementaryClaimResponse response = service.financeReview(claim.getClaimId(), Decision.APPROVE, null);

        assertEquals(SupplementaryExpenseClaim.SETTLED, response.status());
        assertEquals(c.destination().getAdvanceId(), response.settledAdvanceId());
        assertEquals(0, new BigDecimal("1000").compareTo(response.advanceOffsetAmount()));
        var saved = org.mockito.ArgumentCaptor.forClass(com.expense_management_service.entity.CashAdvanceAdjustment.class);
        verify(cashAdvanceAdjustmentRepository).save(saved.capture());
        assertEquals(c.destination(), saved.getValue().getCashAdvance());
        assertEquals(c.report(), saved.getValue().getReport());
        assertEquals(0, new BigDecimal("1000").compareTo(saved.getValue().getAdjustedAmount()));
        assertEquals(com.expense_management_service.entity.CashAdvanceAdjustment.SUPPLEMENTARY_CLAIM, saved.getValue().getPaymentMethod());
        org.junit.jupiter.api.Assertions.assertFalse(saved.getValue().countsForReport(), "does not change the original report's figures");
        verify(cashAdvanceBalanceLedger).applyBalance(eq(c.destination()), eq(new BigDecimal("500")), eq("SUPPLEMENTARY_CLAIM_OFFSET"), any(), any());
        verify(paymentService, never()).requestPayment(any(SupplementaryExpenseClaim.class));
        verify(paymentService, never()).requestPayment(any(SupplementaryExpenseClaim.class), any(BigDecimal.class));
    }

    @Test
    void lateBillLargerThanTheNextAdvanceHolds_restIsPaidByAp() {
        Carried c = carriedForward("IN_PROGRESS", "1500");
        SupplementaryExpenseClaim claim = lateBill(c.report(), "2000");

        SupplementaryClaimResponse response = service.financeReview(claim.getClaimId(), Decision.APPROVE, null);

        assertEquals(SupplementaryExpenseClaim.PAYMENT_PENDING, response.status());
        assertEquals(0, new BigDecimal("1500").compareTo(response.advanceOffsetAmount()));
        verify(paymentService).requestPayment(any(SupplementaryExpenseClaim.class), eq(new BigDecimal("500")));
    }

    @Test
    void lateBillWhenTheNextAdvanceIsClosed_isPaidInFullByAp() {
        Carried c = carriedForward("CLOSED", "0");
        when(carryForwardRepository.findBySourceAdvance_AdvanceIdAndStatusIn(eq(c.destination().getAdvanceId()), anyCollection()))
                .thenReturn(List.of());
        SupplementaryExpenseClaim claim = lateBill(c.report(), "1000");

        SupplementaryClaimResponse response = service.financeReview(claim.getClaimId(), Decision.APPROVE, null);

        assertEquals(SupplementaryExpenseClaim.PAYMENT_PENDING, response.status());
        verify(paymentService).requestPayment(any(SupplementaryExpenseClaim.class));
        verify(cashAdvanceAdjustmentRepository, never()).save(any());
    }

    @Test
    void afterAnAppliedCarryForward_theReportIsEligible_andSaysWhichAdvanceTakesTheClaim() {
        Carried c = carriedForward("IN_PROGRESS", "1500");
        actAs(EMPLOYEE);
        when(reportRepository.findById(c.report().getReportId())).thenReturn(Optional.of(c.report()));
        when(carryForwardRepository.findByDestinationAdvance_AdvanceIdAndStatusIn(any(), anyCollection())).thenReturn(List.of());

        var eligibility = service.checkEligibility(c.report().getReportId());

        org.junit.jupiter.api.Assertions.assertTrue(eligibility.eligible(), eligibility.reason());
        assertEquals(c.destination().getAdvanceId(), eligibility.settleAgainstAdvanceId());
        assertEquals("Advance 2", eligibility.settleAgainstAdvanceTitle());
        assertEquals(0, new BigDecimal("1500").compareTo(eligibility.settleAgainstRemaining()));
    }

    @Test
    void lateBillWhenTheNextAdvanceIsAlreadyInUse_isPaidByAp_andTheAdvanceIsNotTouched() {
        Carried c = carriedForward("IN_PROGRESS", "1500");
        // Advance 2 already has its own report in approval.
        ExpenseReport onNext = settledReport(ReportStatus.PENDING_APPROVAL, new BigDecimal("2000"));
        when(reportRepository.findByCashAdvance_AdvanceIdOrderByCreatedAtAsc(c.destination().getAdvanceId())).thenReturn(List.of(onNext));
        SupplementaryExpenseClaim claim = lateBill(c.report(), "1000");

        SupplementaryClaimResponse response = service.financeReview(claim.getClaimId(), Decision.APPROVE, null);

        assertEquals(SupplementaryExpenseClaim.PAYMENT_PENDING, response.status());
        verify(paymentService).requestPayment(any(SupplementaryExpenseClaim.class));
        verify(cashAdvanceAdjustmentRepository, never()).save(any());
        verify(cashAdvanceBalanceLedger, never()).applyBalance(any(), any(), any(), any(), any());
    }

    @Test
    void aDraftOnTheNextAdvanceDoesNotCountAsUsingIt() {
        Carried c = carriedForward("IN_PROGRESS", "1500");
        ExpenseReport draft = settledReport(ReportStatus.DRAFT, new BigDecimal("300"));
        when(reportRepository.findByCashAdvance_AdvanceIdOrderByCreatedAtAsc(c.destination().getAdvanceId())).thenReturn(List.of(draft));
        SupplementaryExpenseClaim claim = lateBill(c.report(), "1000");

        assertEquals(SupplementaryExpenseClaim.SETTLED, service.financeReview(claim.getClaimId(), Decision.APPROVE, null).status());
    }
}

