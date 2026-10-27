package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.dto.response.SalaryDeductionResponse;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceRecoveryCase;
import com.expense_management_service.entity.CashAdvanceRepayment;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.OutboundEmail;
import com.expense_management_service.entity.SalaryDeductionRequest;
import com.expense_management_service.integration.ums.UmsClient;
import com.expense_management_service.repository.CashAdvanceRecoveryCaseRepository;
import com.expense_management_service.repository.CashAdvanceRepaymentRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.repository.OutboundEmailRepository;
import com.expense_management_service.repository.SalaryDeductionRequestRepository;
import com.expense_management_service.repository.SystemConfigurationRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalaryDeductionServiceTest {

    private static final String EMPLOYEE = "5100014";
    private static final String FINANCE_USER = "FIN001";
    private static final String PAYROLL_EMAIL = "payroll@company.test";
    private static final UUID OUTBOX_ID = UUID.randomUUID();

    @Mock private CashAdvanceRepository cashAdvanceRepository;
    @Mock private SalaryDeductionRequestRepository requestRepository;
    @Mock private CashAdvanceRecoveryCaseRepository recoveryCaseRepository;
    @Mock private CashAdvanceRepaymentRepository repaymentRepository;
    @Mock private CashAdvanceBudgetMovements budgetMovements;
    @Mock private CashAdvanceBalanceLedger balanceLedger;
    @Mock private OutboundEmailRepository outboundEmailRepository;
    @Mock private SystemConfigurationRepository systemConfigurationRepository;
    @Mock private UmsClient umsClient;
    @Mock private CurrentUserService currentUserService;
    @Mock private StorageService storageService;
    @Mock private com.expense_management_service.integration.email.EmailTransport emailTransport;

    private SalaryDeductionService service;
    private CashAdvance advance;
    private UUID advanceId;
    private String payrollMonth;

    @BeforeEach
    void setUp() {
        setFinanceRole("ROLE_FINANCE");
        buildService(PAYROLL_EMAIL);

        advanceId = UUID.randomUUID();
        advance = CashAdvance.builder().advanceId(advanceId).employeeId(EMPLOYEE)
                .amount(new BigDecimal("5000.00")).outstandingBalance(new BigDecimal("4163.00"))
                .status("DISBURSED")
                .currency(Currency.builder().currencyCode("INR").decimalPlaces(2).build())
                .build();
        payrollMonth = YearMonth.now().plusMonths(1).toString();

        lenient().when(currentUserService.getActorId()).thenReturn(FINANCE_USER);
        lenient().when(cashAdvanceRepository.findByIdForUpdate(advanceId)).thenReturn(Optional.of(advance));
        lenient().when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));
        lenient().when(requestRepository.findByAdvance_AdvanceIdOrderByRequestedAtDesc(advanceId)).thenReturn(List.of());
        lenient().when(recoveryCaseRepository.findFirstByAdvance_AdvanceIdAndStatusIn(eq(advanceId), anyCollection()))
                .thenReturn(Optional.empty());
        lenient().when(outboundEmailRepository.save(any(OutboundEmail.class))).thenAnswer(inv -> {
            OutboundEmail e = inv.getArgument(0);
            if (e.getOutboundEmailId() == null) e.setOutboundEmailId(OUTBOX_ID);
            return e;
        });
        lenient().when(requestRepository.save(any(SalaryDeductionRequest.class))).thenAnswer(inv -> {
            SalaryDeductionRequest r = inv.getArgument(0);
            if (r.getSalaryDeductionRequestId() == null) r.setSalaryDeductionRequestId(UUID.randomUUID());
            return r;
        });
        lenient().when(cashAdvanceRepository.save(any(CashAdvance.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** Builds the service with the given default payroll address. An empty address simulates "not configured". */
    private void buildService(String payrollEmail) {
        PolicySettingsService policy = new PolicySettingsService(systemConfigurationRepository);
        ReflectionTestUtils.setField(policy, "defaultPayrollReportEmail", payrollEmail);
        EmailOutboxService outbox = new EmailOutboxService(outboundEmailRepository, emailTransport);
        CashAdvancePayrollPosting posting = new CashAdvancePayrollPosting(cashAdvanceRepository, repaymentRepository,
                budgetMovements, balanceLedger, storageService);
        service = new SalaryDeductionService(cashAdvanceRepository, requestRepository, recoveryCaseRepository, posting,
                new PayrollMonthPolicy(12, 0), outbox, balanceLedger, policy, umsClient, currentUserService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void setFinanceRole(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                FINANCE_USER, "n/a", List.of(new SimpleGrantedAuthority(role))));
    }

    private SalaryDeductionRequest request(String status, String requested, boolean sentToPayroll, boolean outcomeRecorded) {
        SalaryDeductionRequest r = SalaryDeductionRequest.builder()
                .salaryDeductionRequestId(UUID.randomUUID())
                .payrollRequestId("PR-2026-2027-AAAA1111")
                .advance(advance)
                .employeeId(EMPLOYEE)
                .requestedAmount(new BigDecimal(requested))
                .payrollMonth(payrollMonth)
                .status(status)
                .requestedBy(FINANCE_USER)
                .requestedAt(LocalDateTime.of(2026, 10, 26, 9, 0))
                .sentToPayroll(sentToPayroll)
                .outcomeRecorded(outcomeRecorded)
                .outboundEmailId(OUTBOX_ID)
                .build();
        lenient().when(requestRepository.findById(r.getSalaryDeductionRequestId())).thenReturn(Optional.of(r));
        lenient().when(requestRepository.findByIdForUpdate(r.getSalaryDeductionRequestId())).thenReturn(Optional.of(r));
        return r;
    }

    private void outboxStatus(String status) {
        OutboundEmail email = OutboundEmail.builder().outboundEmailId(OUTBOX_ID).status(status).build();
        lenient().when(outboundEmailRepository.findById(OUTBOX_ID)).thenReturn(Optional.of(email));
        lenient().when(outboundEmailRepository.findByIdForUpdate(OUTBOX_ID)).thenReturn(Optional.of(email));
    }

    // ------------------------------------------------------------ create

    @Test
    void create_savesRequest_queuesEmailToPayroll_andNeedsNoAuthorizationOrPayrollReference() {
        outboxStatus(OutboundEmail.PENDING);

        SalaryDeductionResponse response = service.create(advanceId, new BigDecimal("4163.00"), payrollMonth);

        assertThat(response.payrollRequestId()).matches("PR-\\d{4}-\\d{4}-[0-9A-F]{8}");
        assertThat(response.requestedAmount()).isEqualByComparingTo("4163.00");
        assertThat(response.payrollMonth()).isEqualTo(payrollMonth);
        assertThat(response.status()).isEqualTo(SalaryDeductionRequest.PENDING_SEND);
        assertThat(response.canCancel()).isTrue();
        assertThat(response.canRecordResult()).isFalse();

        ArgumentCaptor<OutboundEmail> email = ArgumentCaptor.forClass(OutboundEmail.class);
        verify(outboundEmailRepository).save(email.capture());
        OutboundEmail sent = email.getValue();
        assertThat(sent.getToAddress()).isEqualTo(PAYROLL_EMAIL);
        assertThat(sent.getEmailType()).isEqualTo("SALARY_DEDUCTION_REQUEST");
        assertThat(sent.getReferenceId()).isEqualTo(response.payrollRequestId());
        assertThat(sent.getBody()).contains("Employee ID: " + EMPLOYEE)
                .contains("Cash Advance ID: " + advanceId)
                .contains("Requested Deduction Amount: 4163.00 INR")
                .contains("Payroll Month: " + payrollMonth)
                .contains(response.payrollRequestId())
                .contains("Payroll Component: Cash Advance Recovery")
                .contains("\"Cash Advance Recovery\" deduction component")
                .doesNotContain("Authorization")
                .doesNotContain("authorization");
    }

    @Test
    void create_takesTheAdvanceLockBeforeCheckingForOpenRequests() {
        outboxStatus(OutboundEmail.PENDING);

        service.create(advanceId, new BigDecimal("100.00"), payrollMonth);

        verify(cashAdvanceRepository).findByIdForUpdate(advanceId);
        verify(cashAdvanceRepository, never()).findById(advanceId);
    }

    @Test
    void create_rejectsZeroAndNegativeAmounts() {
        assertThatThrownBy(() -> service.create(advanceId, BigDecimal.ZERO, payrollMonth))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(advanceId, new BigDecimal("-5"), payrollMonth))
                .isInstanceOf(IllegalArgumentException.class);
        verify(outboundEmailRepository, never()).save(any());
    }

    @Test
    void create_rejectsAmountAboveOutstandingBalance() {
        assertThatThrownBy(() -> service.create(advanceId, new BigDecimal("5000.00"), payrollMonth))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("cannot exceed the outstanding balance");
    }

    @Test
    void create_acceptsPartialAmount() {
        outboxStatus(OutboundEmail.PENDING);

        SalaryDeductionResponse response = service.create(advanceId, new BigDecimal("2000.00"), payrollMonth);

        assertThat(response.requestedAmount()).isEqualByComparingTo("2000.00");
    }

    @Test
    void create_rejectsMoreThanTwoDecimalPlaces() {
        assertThatThrownBy(() -> service.create(advanceId, new BigDecimal("10.005"), payrollMonth))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decimal places");
    }

    @Test
    void create_rejectsMissingOrInvalidPayrollMonth() {
        assertThatThrownBy(() -> service.create(advanceId, new BigDecimal("100.00"), null))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> service.create(advanceId, new BigDecimal("100.00"), "2026-13"))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void create_rejectsPayrollMonthOutsideTheWindow() {
        String pastMonth = YearMonth.now().minusMonths(1).toString();
        String beyondWindow = YearMonth.now().plusMonths(12).toString();

        assertThatThrownBy(() -> service.create(advanceId, new BigDecimal("100.00"), pastMonth))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("outside the eligible window");
        assertThatThrownBy(() -> service.create(advanceId, new BigDecimal("100.00"), beyondWindow))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void create_refusesNonFinanceUsers() {
        setFinanceRole("ROLE_GENERAL");

        assertThatThrownBy(() -> service.create(advanceId, new BigDecimal("100.00"), payrollMonth))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only Finance");
    }

    @Test
    void create_refusesWhenAnotherRequestIsStillOpen() {
        SalaryDeductionRequest open = request(SalaryDeductionRequest.SENT_TO_PAYROLL, "1000.00", true, false);
        when(requestRepository.findByAdvance_AdvanceIdOrderByRequestedAtDesc(advanceId)).thenReturn(List.of(open));

        assertThatThrownBy(() -> service.create(advanceId, new BigDecimal("500.00"), payrollMonth))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("still open");
        verify(outboundEmailRepository, never()).save(any());
    }

    @Test
    void create_allowsANewRequestAfterAFailedPayrollResult() {
        SalaryDeductionRequest failed = request(SalaryDeductionRequest.PAYROLL_FAILED, "4163.00", true, true);
        when(requestRepository.findByAdvance_AdvanceIdOrderByRequestedAtDesc(advanceId)).thenReturn(List.of(failed));
        outboxStatus(OutboundEmail.PENDING);

        assertThat(service.create(advanceId, new BigDecimal("4163.00"), payrollMonth).status())
                .isEqualTo(SalaryDeductionRequest.PENDING_SEND);
    }

    @Test
    void create_refusesWhenAnAutomatedRecoveryCaseIsOpen() {
        when(recoveryCaseRepository.findFirstByAdvance_AdvanceIdAndStatusIn(eq(advanceId), anyCollection()))
                .thenReturn(Optional.of(CashAdvanceRecoveryCase.builder().status(CashAdvanceRecoveryCase.NOTICE_SENT).build()));

        assertThatThrownBy(() -> service.create(advanceId, new BigDecimal("100.00"), payrollMonth))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("automated refund notice");
    }

    @Test
    void create_refusesAnAdvanceThatIsNotDisbursed() {
        advance.setStatus("APPROVED");

        assertThatThrownBy(() -> service.create(advanceId, new BigDecimal("100.00"), payrollMonth))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("not available");
    }

    @Test
    void create_refusesWhenNoPayrollEmailIsConfigured() {
        buildService("");

        assertThatThrownBy(() -> service.create(advanceId, new BigDecimal("100.00"), payrollMonth))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("No payroll email");
    }

    // ------------------------------------------------------------ email retry

    @Test
    void retryEmail_queuesAgainOnlyWhenTheLastAttemptFailed() {
        SalaryDeductionRequest r = request(SalaryDeductionRequest.PENDING_SEND, "100.00", false, false);
        outboxStatus(OutboundEmail.FAILED);
        when(outboundEmailRepository.findByEmailTypeAndReferenceId("SALARY_DEDUCTION_REQUEST", "PR-2026-2027-AAAA1111"))
                .thenReturn(List.of(OutboundEmail.builder().status(OutboundEmail.FAILED).build()));

        service.retryEmail(r.getSalaryDeductionRequestId());

        verify(outboundEmailRepository).save(any(OutboundEmail.class));
    }

    @Test
    void retryEmail_refusesWhileAMessageIsStillQueued_soItIsNotSentTwice() {
        SalaryDeductionRequest r = request(SalaryDeductionRequest.PENDING_SEND, "100.00", false, false);
        outboxStatus(OutboundEmail.PENDING);
        when(outboundEmailRepository.findByEmailTypeAndReferenceId("SALARY_DEDUCTION_REQUEST", "PR-2026-2027-AAAA1111"))
                .thenReturn(List.of(OutboundEmail.builder().status(OutboundEmail.PENDING).build()));

        assertThatThrownBy(() -> service.retryEmail(r.getSalaryDeductionRequestId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("not sent twice");
        verify(outboundEmailRepository, never()).save(any());
    }

    @Test
    void retryEmail_refusesOnceTheRequestHasReachedPayroll() {
        SalaryDeductionRequest r = request(SalaryDeductionRequest.SENT_TO_PAYROLL, "100.00", true, false);

        assertThatThrownBy(() -> service.retryEmail(r.getSalaryDeductionRequestId()))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    // ------------------------------------------------------------ cancel

    @Test
    void cancel_beforeDelivery_withdrawsTheEmailAndLeavesPayrollUntouched() {
        SalaryDeductionRequest r = request(SalaryDeductionRequest.PENDING_SEND, "100.00", false, false);
        outboxStatus(OutboundEmail.PENDING);

        SalaryDeductionResponse response = service.cancel(r.getSalaryDeductionRequestId(), "Entered in error");

        assertThat(response.status()).isEqualTo(SalaryDeductionRequest.CANCELLED);
        assertThat(response.sentToPayroll()).isFalse();
        assertThat(response.open()).isFalse();
        assertThat(response.cancellationReason()).isEqualTo("Entered in error");
        assertThat(response.cancelledBy()).isEqualTo(FINANCE_USER);
        verify(outboundEmailRepository, never()).save(argThatIsCancellationEmail());
    }

    @Test
    void cancel_afterDelivery_sendsACancellationNoticeAndStaysOpenUntilTheResultIsRecorded() {
        SalaryDeductionRequest r = request(SalaryDeductionRequest.SENT_TO_PAYROLL, "100.00", true, false);

        SalaryDeductionResponse response = service.cancel(r.getSalaryDeductionRequestId(), "Wrong amount");

        assertThat(response.status()).isEqualTo(SalaryDeductionRequest.CANCELLED);
        assertThat(response.sentToPayroll()).isTrue();
        assertThat(response.open()).isTrue();          // blocks a replacement until Payroll's outcome is recorded
        assertThat(response.canRecordResult()).isTrue();
        ArgumentCaptor<OutboundEmail> email = ArgumentCaptor.forClass(OutboundEmail.class);
        verify(outboundEmailRepository, org.mockito.Mockito.atLeastOnce()).save(email.capture());
        assertThat(email.getAllValues()).anyMatch(e -> "SALARY_DEDUCTION_CANCELLATION".equals(e.getEmailType()));
    }

    @Test
    void cancel_requiresAReason() {
        SalaryDeductionRequest r = request(SalaryDeductionRequest.PENDING_SEND, "100.00", false, false);

        assertThatThrownBy(() -> service.cancel(r.getSalaryDeductionRequestId(), "  "))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void cancel_refusesAfterPayrollConfirmed() {
        SalaryDeductionRequest r = request(SalaryDeductionRequest.PAYROLL_CONFIRMED, "100.00", true, true);

        assertThatThrownBy(() -> service.cancel(r.getSalaryDeductionRequestId(), "Too late"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("cannot be cancelled");
    }

    // ------------------------------------------------------------ Payroll result

    @Test
    void result_fullDeduction_postsTheAmountAndClosesTheAdvance() {
        SalaryDeductionRequest r = request(SalaryDeductionRequest.SENT_TO_PAYROLL, "4163.00", true, false);
        outboxStatus(OutboundEmail.SENT);

        SalaryDeductionResponse response = service.recordResult(r.getSalaryDeductionRequestId(), new BigDecimal("4163.00"),
                payrollMonth, "PAY-REF-1", null, null, null);

        assertThat(response.status()).isEqualTo(SalaryDeductionRequest.PAYROLL_CONFIRMED);
        assertThat(response.outcomeRecorded()).isTrue();
        assertThat(response.shortfall()).isEqualByComparingTo("0.00");
        assertThat(advance.getOutstandingBalance()).isEqualByComparingTo("0.00");
        assertThat(advance.getStatus()).isEqualTo("CLOSED");
        ArgumentCaptor<CashAdvanceRepayment> repayment = ArgumentCaptor.forClass(CashAdvanceRepayment.class);
        verify(repaymentRepository).save(repayment.capture());
        assertThat(repayment.getValue().getAmount()).isEqualByComparingTo("4163.00");
        assertThat(repayment.getValue().getPaymentMode()).isEqualTo("PAYROLL");
        assertThat(repayment.getValue().getPaymentReference()).isEqualTo("PAY-REF-1");
    }

    @Test
    void result_partialDeduction_postsOnlyTheActualAmount_andLeavesTheAdvanceOpen() {
        SalaryDeductionRequest r = request(SalaryDeductionRequest.SENT_TO_PAYROLL, "4163.00", true, false);
        outboxStatus(OutboundEmail.SENT);

        SalaryDeductionResponse response = service.recordResult(r.getSalaryDeductionRequestId(), new BigDecimal("3000.00"),
                payrollMonth, "PAY-REF-2", null, null, null);

        assertThat(response.status()).isEqualTo(SalaryDeductionRequest.PAYROLL_CONFIRMED);
        assertThat(response.shortfall()).isEqualByComparingTo("1163.00");
        assertThat(advance.getOutstandingBalance()).isEqualByComparingTo("1163.00");
        assertThat(advance.getStatus()).isEqualTo("SETTLEMENT_PENDING");
    }

    @Test
    void result_zeroDeduction_recordsFailure_andLeavesTheBalanceUnchanged() {
        SalaryDeductionRequest r = request(SalaryDeductionRequest.SENT_TO_PAYROLL, "4163.00", true, false);
        outboxStatus(OutboundEmail.SENT);

        SalaryDeductionResponse response = service.recordResult(r.getSalaryDeductionRequestId(), BigDecimal.ZERO,
                payrollMonth, "PAY-REF-3", null, null, null);

        assertThat(response.status()).isEqualTo(SalaryDeductionRequest.PAYROLL_FAILED);
        assertThat(advance.getOutstandingBalance()).isEqualByComparingTo("4163.00");
        verify(repaymentRepository, never()).save(any());
    }

    @Test
    void result_duplicateRecording_isRefused_soTheDeductionIsNotPostedTwice() {
        SalaryDeductionRequest r = request(SalaryDeductionRequest.SENT_TO_PAYROLL, "4163.00", true, false);
        outboxStatus(OutboundEmail.SENT);
        service.recordResult(r.getSalaryDeductionRequestId(), new BigDecimal("1000.00"), payrollMonth, "REF", null, null, null);

        assertThatThrownBy(() -> service.recordResult(r.getSalaryDeductionRequestId(), new BigDecimal("1000.00"),
                payrollMonth, "REF", null, null, null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already recorded");
        assertThat(advance.getOutstandingBalance()).isEqualByComparingTo("3163.00");
        verify(repaymentRepository).save(any(CashAdvanceRepayment.class));
    }

    @Test
    void result_rejectsAnActualAmountAboveTheRequestAndAboveTheBalance() {
        SalaryDeductionRequest r = request(SalaryDeductionRequest.SENT_TO_PAYROLL, "1000.00", true, false);
        outboxStatus(OutboundEmail.SENT);

        assertThatThrownBy(() -> service.recordResult(r.getSalaryDeductionRequestId(), new BigDecimal("1500.00"),
                payrollMonth, "REF", null, null, null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("cannot exceed the requested amount");
        assertThat(advance.getOutstandingBalance()).isEqualByComparingTo("4163.00");
    }

    @Test
    void result_rejectsNegativeActualAmount() {
        SalaryDeductionRequest r = request(SalaryDeductionRequest.SENT_TO_PAYROLL, "1000.00", true, false);
        outboxStatus(OutboundEmail.SENT);

        assertThatThrownBy(() -> service.recordResult(r.getSalaryDeductionRequestId(), new BigDecimal("-1"),
                payrollMonth, "REF", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void result_requiresAReferenceOrAlternativeEvidence() {
        SalaryDeductionRequest r = request(SalaryDeductionRequest.SENT_TO_PAYROLL, "1000.00", true, false);
        outboxStatus(OutboundEmail.SENT);

        assertThatThrownBy(() -> service.recordResult(r.getSalaryDeductionRequestId(), new BigDecimal("1000.00"),
                payrollMonth, null, null, null, null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Payroll confirmation reference");
        verify(repaymentRepository, never()).save(any());
    }

    @Test
    void result_withoutReference_isAcceptedWithEvidenceTypeAndFile_andStoresTheFile() {
        SalaryDeductionRequest r = request(SalaryDeductionRequest.SENT_TO_PAYROLL, "1000.00", true, false);
        outboxStatus(OutboundEmail.SENT);
        MockMultipartFile payslip = new MockMultipartFile("evidenceFile", "payslip.pdf", "application/pdf", new byte[]{1, 2, 3});

        SalaryDeductionResponse response = service.recordResult(r.getSalaryDeductionRequestId(), new BigDecimal("1000.00"),
                payrollMonth, null, "PAYSLIP", null, payslip);

        assertThat(response.payrollReference()).isNull();
        assertThat(response.evidenceType()).isEqualTo("PAYSLIP");
        assertThat(response.evidenceAttached()).isTrue();
        verify(storageService).upload(any(String.class), eq(payslip));
    }

    @Test
    void result_withoutReference_needsAFileOrANote() {
        SalaryDeductionRequest r = request(SalaryDeductionRequest.SENT_TO_PAYROLL, "1000.00", true, false);
        outboxStatus(OutboundEmail.SENT);

        assertThatThrownBy(() -> service.recordResult(r.getSalaryDeductionRequestId(), new BigDecimal("1000.00"),
                payrollMonth, null, "PAYSLIP", null, null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Attach the evidence file");
    }

    @Test
    void result_requiresTheSameMonthThatWasRequested() {
        SalaryDeductionRequest r = request(SalaryDeductionRequest.SENT_TO_PAYROLL, "1000.00", true, false);
        outboxStatus(OutboundEmail.SENT);

        assertThatThrownBy(() -> service.recordResult(r.getSalaryDeductionRequestId(), new BigDecimal("1000.00"),
                YearMonth.now().plusMonths(2).toString(), "REF", null, null, null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("must match the requested month");
    }

    @Test
    void result_isRefusedBeforeTheRequestHasReachedPayroll() {
        SalaryDeductionRequest r = request(SalaryDeductionRequest.PENDING_SEND, "1000.00", false, false);
        outboxStatus(OutboundEmail.PENDING);

        assertThatThrownBy(() -> service.recordResult(r.getSalaryDeductionRequestId(), new BigDecimal("1000.00"),
                payrollMonth, "REF", null, null, null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("only after the request has reached Payroll");
        assertThat(advance.getOutstandingBalance()).isEqualByComparingTo("4163.00");
    }

    @Test
    void result_afterCancellationOfASentRequest_isAllowed_andKeepsTheCancelledStatus() {
        SalaryDeductionRequest r = request(SalaryDeductionRequest.CANCELLED, "1000.00", true, false);

        SalaryDeductionResponse response = service.recordResult(r.getSalaryDeductionRequestId(), new BigDecimal("1000.00"),
                payrollMonth, "REF", null, null, null);

        assertThat(response.status()).isEqualTo(SalaryDeductionRequest.CANCELLED);
        assertThat(response.outcomeRecorded()).isTrue();
        assertThat(response.open()).isFalse();
        assertThat(advance.getOutstandingBalance()).isEqualByComparingTo("3163.00");
    }

    @Test
    void result_locksTheAdvanceBeforeTheRequest() {
        SalaryDeductionRequest r = request(SalaryDeductionRequest.SENT_TO_PAYROLL, "1000.00", true, false);
        outboxStatus(OutboundEmail.SENT);

        service.recordResult(r.getSalaryDeductionRequestId(), new BigDecimal("1000.00"), payrollMonth, "REF", null, null, null);

        verify(cashAdvanceRepository).findByIdForUpdate(advanceId);
        verify(requestRepository).findByIdForUpdate(r.getSalaryDeductionRequestId());
    }

    // ------------------------------------------------------------ status sync

    @Test
    void list_movesARequestToSentWhenTheOutboxHasDeliveredIt() {
        SalaryDeductionRequest r = request(SalaryDeductionRequest.PENDING_SEND, "100.00", false, false);
        OutboundEmail delivered = OutboundEmail.builder().outboundEmailId(OUTBOX_ID).status(OutboundEmail.SENT)
                .sentAt(LocalDateTime.of(2026, 10, 26, 9, 5)).build();
        when(outboundEmailRepository.findById(OUTBOX_ID)).thenReturn(Optional.of(delivered));
        when(requestRepository.findByAdvance_AdvanceIdOrderByRequestedAtDesc(advanceId)).thenReturn(List.of(r));

        List<SalaryDeductionResponse> list = service.listForAdvance(advanceId);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).status()).isEqualTo(SalaryDeductionRequest.SENT_TO_PAYROLL);
        assertThat(list.get(0).sentToPayroll()).isTrue();
        assertThat(list.get(0).emailStatus()).isEqualTo("SENT");
    }

    @Test
    void list_showsAFailedEmailAndOffersARetry() {
        SalaryDeductionRequest r = request(SalaryDeductionRequest.PENDING_SEND, "100.00", false, false);
        outboxStatus(OutboundEmail.FAILED);
        when(requestRepository.findByAdvance_AdvanceIdOrderByRequestedAtDesc(advanceId)).thenReturn(List.of(r));

        SalaryDeductionResponse response = service.listForAdvance(advanceId).get(0);

        assertThat(response.status()).isEqualTo(SalaryDeductionRequest.PENDING_SEND);
        assertThat(response.emailStatus()).isEqualTo("FAILED");
        assertThat(response.canRetryEmail()).isTrue();
    }

    @Test
    void reads_refuseNonFinanceUsers() {
        setFinanceRole("ROLE_GENERAL");

        assertThatThrownBy(() -> service.listForAdvance(advanceId)).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> service.eligiblePayrollMonths()).isInstanceOf(BusinessRuleViolationException.class);
    }

    // ------------------------------------------------------------ automatic request from a recovery case

    @Test
    void initiateForRecoveryCase_createsASystemRequestLinkedToTheCase() {
        UUID caseId = UUID.randomUUID();
        outboxStatus(OutboundEmail.PENDING);

        var request = service.initiateForRecoveryCase(advance, caseId, new BigDecimal("4163.00"), LocalDate.now());

        assertThat(request).isPresent();
        assertThat(request.get().getRecoveryCaseId()).isEqualTo(caseId);
        assertThat(request.get().getRequestedBy()).isEqualTo("SYSTEM");
        assertThat(request.get().getRequestedAmount()).isEqualByComparingTo("4163.00");
        assertThat(request.get().getPayrollMonth()).isEqualTo(YearMonth.now().toString());
        assertThat(request.get().getStatus()).isEqualTo(SalaryDeductionRequest.PENDING_SEND);
    }

    @Test
    void initiateForRecoveryCase_returnsEmptyWhenNoPayrollEmailIsConfigured() {
        buildService("");

        assertThat(service.initiateForRecoveryCase(advance, UUID.randomUUID(), new BigDecimal("100.00"), LocalDate.now())).isEmpty();
        verify(requestRepository, never()).save(any());
    }

    @Test
    void result_onALinkedRecoveryCase_closesTheCaseWithTheActualAmount() {
        UUID caseId = UUID.randomUUID();
        CashAdvanceRecoveryCase recoveryCase = CashAdvanceRecoveryCase.builder()
                .recoveryCaseId(caseId).status(CashAdvanceRecoveryCase.SENT_TO_PAYROLL).build();
        when(recoveryCaseRepository.findByIdForUpdate(caseId)).thenReturn(Optional.of(recoveryCase));
        SalaryDeductionRequest r = request(SalaryDeductionRequest.SENT_TO_PAYROLL, "4163.00", true, false);
        r.setRecoveryCaseId(caseId);
        outboxStatus(OutboundEmail.SENT);

        service.recordResult(r.getSalaryDeductionRequestId(), new BigDecimal("3000.00"), payrollMonth, "REF", null, null, null);

        assertThat(recoveryCase.getStatus()).isEqualTo(CashAdvanceRecoveryCase.PAYROLL_RECORDED);
        assertThat(recoveryCase.getPayrollActualDeducted()).isEqualByComparingTo("3000.00");
        assertThat(advance.getOutstandingBalance()).isEqualByComparingTo("1163.00");
    }

    @Test
    void cancelBeforeDelivery_ofALinkedRequest_closesTheRecoveryCase_soItIsNotRaisedAgainByItself() {
        UUID caseId = UUID.randomUUID();
        CashAdvanceRecoveryCase recoveryCase = CashAdvanceRecoveryCase.builder()
                .recoveryCaseId(caseId).status(CashAdvanceRecoveryCase.SENT_TO_PAYROLL).build();
        when(recoveryCaseRepository.findByIdForUpdate(caseId)).thenReturn(Optional.of(recoveryCase));
        SalaryDeductionRequest r = request(SalaryDeductionRequest.PENDING_SEND, "4163.00", false, false);
        r.setRecoveryCaseId(caseId);
        outboxStatus(OutboundEmail.PENDING);

        service.cancel(r.getSalaryDeductionRequestId(), "Employee paid in cash");

        assertThat(recoveryCase.getStatus()).isEqualTo(CashAdvanceRecoveryCase.CANCELLED);
        assertThat(recoveryCase.getResolutionNote()).contains("Employee paid in cash");
    }

    private static OutboundEmail argThatIsCancellationEmail() {
        return org.mockito.ArgumentMatchers.argThat(e -> e != null && "SALARY_DEDUCTION_CANCELLATION".equals(e.getEmailType()));
    }
}
