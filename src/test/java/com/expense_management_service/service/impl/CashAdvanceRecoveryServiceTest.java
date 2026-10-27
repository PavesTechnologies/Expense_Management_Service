package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceAdjustment;
import com.expense_management_service.entity.CashAdvanceRecoveryCase;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.OutboundEmail;
import com.expense_management_service.entity.SystemConfiguration;
import com.expense_management_service.integration.email.EmailTransport;
import com.expense_management_service.integration.email.EmailTransportException;
import com.expense_management_service.integration.ums.UmsClient;
import com.expense_management_service.integration.ums.dto.UmsUserResponse;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.CashAdvanceAdjustmentRepository;
import com.expense_management_service.repository.CashAdvanceRecoveryCaseRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.repository.OutboundEmailRepository;
import com.expense_management_service.repository.SystemConfigurationRepository;
import com.expense_management_service.security.CurrentUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CashAdvanceRecoveryServiceTest {

    private static final String EMPLOYEE = "5100014";
    private static final String PAYROLL = "payroll@company.test";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 26, 9, 0);

    @Mock private CashAdvanceRepository cashAdvanceRepository;
    @Mock private CashAdvanceAdjustmentRepository adjustmentRepository;
    @Mock private CashAdvanceRecoveryCaseRepository caseRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private OutboundEmailRepository outboundEmailRepository;
    @Mock private EmailTransport emailTransport;
    @Mock private SystemConfigurationRepository systemConfigurationRepository;
    @Mock private UmsClient umsClient;
    @Mock private CurrentUserService currentUserService;
    @Mock private com.expense_management_service.service.NotificationService notificationService;
    @Mock private com.expense_management_service.repository.SalaryDeductionRequestRepository salaryDeductionRequestRepository;
    @Mock private SalaryDeductionService salaryDeductionService;

    private CashAdvanceRecoveryService service;
    private EmailOutboxService outbox;
    private CashAdvance advance;
    private UUID advanceId;

    @BeforeEach
    void setUp() {
        outbox = new EmailOutboxService(outboundEmailRepository, emailTransport);
        lenient().when(emailTransport.isConfigured()).thenReturn(true);
        PolicySettingsService policy = new PolicySettingsService(systemConfigurationRepository);
        service = new CashAdvanceRecoveryService(cashAdvanceRepository, adjustmentRepository, caseRepository,
                new CashAdvanceBalanceLedger(auditLogRepository), outbox, policy, umsClient, currentUserService,
                notificationService, salaryDeductionRequestRepository, salaryDeductionService);

        advanceId = UUID.randomUUID();
        advance = CashAdvance.builder().advanceId(advanceId).employeeId(EMPLOYEE)
                .amount(new BigDecimal("1000.00")).outstandingBalance(new BigDecimal("800.00"))
                .status("SETTLEMENT_PENDING")
                .currency(Currency.builder().currencyCode("INR").decimalPlaces(2).build())
                .build();

        lenient().when(outboundEmailRepository.save(any(OutboundEmail.class))).thenAnswer(inv -> {
            OutboundEmail e = inv.getArgument(0);
            if (e.getOutboundEmailId() == null) e.setOutboundEmailId(UUID.randomUUID());
            return e;
        });
        lenient().when(caseRepository.save(any(CashAdvanceRecoveryCase.class))).thenAnswer(inv -> {
            CashAdvanceRecoveryCase c = inv.getArgument(0);
            if (c.getRecoveryCaseId() == null) c.setRecoveryCaseId(UUID.randomUUID());
            return c;
        });
        lenient().when(umsClient.getAllUsers()).thenReturn(List.of(
                new UmsUserResponse(UUID.randomUUID(), 5100014L, "Asha", "Rao", "asha@company.test", true)));
        lenient().when(systemConfigurationRepository.findByConfigKey(anyString())).thenReturn(Optional.empty());
        lenient().when(adjustmentRepository.findByCashAdvance_AdvanceId(any())).thenReturn(new ArrayList<>());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void actAs(String id, String... roles) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String role : roles) authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id, null, authorities));
        lenient().when(currentUserService.getActorId()).thenReturn(id);
    }

    private void configure(String key, String value) {
        when(systemConfigurationRepository.findByConfigKey(key)).thenReturn(Optional.of(
                SystemConfiguration.builder().configKey(key).configValue(value).build()));
    }

    private CashAdvanceRecoveryCase openCase(CashAdvance a, LocalDateTime dueAt) {
        return CashAdvanceRecoveryCase.builder()
                .recoveryCaseId(UUID.randomUUID()).advance(a).employeeId(a.getEmployeeId())
                .amountAtNotice(a.getOutstandingBalance()).status(CashAdvanceRecoveryCase.NOTICE_SENT)
                .noticeSentAt(dueAt.minusDays(1)).noticeSentBy("FIN001").dueAt(dueAt).build();
    }

    // ------------------------------------------------------------ route 2: refund notice

    @Test
    void refundNotice_startsTheGracePeriodAndQueuesTheEmployeeNotice() {
        actAs("FIN001", "FINANCE");
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));

        CashAdvanceRecoveryCase created = service.startRefundNotice(advanceId);

        assertThat(created.getStatus()).isEqualTo(CashAdvanceRecoveryCase.NOTICE_SENT);
        assertThat(created.getAmountAtNotice()).isEqualByComparingTo("800.00");
        assertThat(created.getDueAt()).isAfter(created.getNoticeSentAt());
        ArgumentCaptor<OutboundEmail> captor = ArgumentCaptor.forClass(OutboundEmail.class);
        verify(outboundEmailRepository).save(captor.capture());
        assertThat(captor.getValue().getToAddress()).isEqualTo("asha@company.test");
        assertThat(captor.getValue().getEmailType()).isEqualTo("CASH_ADVANCE_RECOVERY_NOTICE");
        assertThat(captor.getValue().getBody()).contains("800.00 INR");
    }

    @Test
    void refundNotice_usesTheGracePeriodFromPolicy() {
        actAs("FIN001", "FINANCE");
        configure(PolicySettingsService.KEY_GRACE_DAYS, "3");
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));

        CashAdvanceRecoveryCase created = service.startRefundNotice(advanceId);

        assertThat(created.getDueAt()).isEqualTo(
                com.expense_management_service.common.BusinessDayCalculator.addBusinessDays(created.getNoticeSentAt(), 3));
    }

    @Test
    void gracePeriodIsWorkingDays_aFridayNoticeIsDueOnTheFollowingMonday() {
        LocalDateTime friday = LocalDateTime.of(2026, 10, 23, 9, 0);   // a Friday
        assertThat(com.expense_management_service.common.BusinessDayCalculator.addBusinessDays(friday, 1))
                .isEqualTo(LocalDateTime.of(2026, 10, 26, 9, 0));       // the following Monday
    }

    @Test
    void refundNotice_isAlsoSentThroughTheNotificationCenter() {
        actAs("FIN001", "FINANCE");
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));

        service.startRefundNotice(advanceId);

        verify(notificationService).notifyEmployee(eq(EMPLOYEE), any());
    }

    @Test
    void refundNotice_refusesWithoutAnEmailAddress_becauseTheNoticeCannotBeSent() {
        actAs("FIN001", "FINANCE");
        when(umsClient.getAllUsers()).thenReturn(List.of(
                new UmsUserResponse(UUID.randomUUID(), 5100014L, "Asha", "Rao", null, true)));
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));

        assertThatThrownBy(() -> service.startRefundNotice(advanceId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("no email address");
        verify(caseRepository, never()).save(any());
    }

    @Test
    void refundNotice_refusesWhenNothingIsOutstanding() {
        actAs("FIN001", "FINANCE");
        advance.setOutstandingBalance(BigDecimal.ZERO);
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));

        assertThatThrownBy(() -> service.startRefundNotice(advanceId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("no outstanding balance");
    }

    @Test
    void refundNotice_refusesASecondOpenCaseForTheSameAdvance() {
        actAs("FIN001", "FINANCE");
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));
        when(caseRepository.findFirstByAdvance_AdvanceIdAndStatusIn(eq(advanceId), any()))
                .thenReturn(Optional.of(openCase(advance, NOW.plusDays(1))));

        assertThatThrownBy(() -> service.startRefundNotice(advanceId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already open");
    }

    @Test
    void refundNotice_isRefusedToNonFinance() {
        actAs(EMPLOYEE, "GENERAL");

        assertThatThrownBy(() -> service.startRefundNotice(advanceId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only Finance");
        verify(outboundEmailRepository, never()).save(any());
    }

    // ------------------------------------------------------------ route 3: automatic salary deduction

    @Test
    void dueCaseWithBalance_raisesASalaryDeductionAutomatically_andMarksTheCaseSentToPayroll() {
        CashAdvanceRecoveryCase due = openCase(advance, NOW.minusHours(1));
        when(caseRepository.findByStatusAndDueAtLessThanEqual(CashAdvanceRecoveryCase.NOTICE_SENT, NOW))
                .thenReturn(List.of(due));
        when(caseRepository.findByIdForUpdate(due.getRecoveryCaseId())).thenReturn(Optional.of(due));
        when(cashAdvanceRepository.findByIdForUpdate(advanceId)).thenReturn(Optional.of(advance));
        when(salaryDeductionService.initiateForRecoveryCase(eq(advance), eq(due.getRecoveryCaseId()),
                eq(new BigDecimal("800.00")), any()))
                .thenReturn(Optional.of(com.expense_management_service.entity.SalaryDeductionRequest.builder()
                        .payrollRequestId("PR-2026-2027-AAAA1111").build()));

        int initiated = service.escalateDueCases(NOW);

        assertThat(initiated).isEqualTo(1);
        assertThat(due.getStatus()).isEqualTo(CashAdvanceRecoveryCase.SENT_TO_PAYROLL);
        assertThat(due.getPayrollReportSentAt()).isEqualTo(NOW);
        assertThat(advance.getOutstandingBalance()).isEqualByComparingTo("800.00"); // the advance itself is not changed
        verify(salaryDeductionService).initiateForRecoveryCase(eq(advance), eq(due.getRecoveryCaseId()),
                eq(new BigDecimal("800.00")), any());
        verify(outboundEmailRepository, never()).save(any(OutboundEmail.class)); // the salary request sends its own email
    }

    @Test
    void dueCaseStaysOpen_whenTheSalaryRequestCannotBeCreated() {
        CashAdvanceRecoveryCase due = openCase(advance, NOW.minusHours(1));
        when(caseRepository.findByStatusAndDueAtLessThanEqual(CashAdvanceRecoveryCase.NOTICE_SENT, NOW))
                .thenReturn(List.of(due));
        when(caseRepository.findByIdForUpdate(due.getRecoveryCaseId())).thenReturn(Optional.of(due));
        when(cashAdvanceRepository.findByIdForUpdate(advanceId)).thenReturn(Optional.of(advance));
        when(salaryDeductionService.initiateForRecoveryCase(any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThat(service.escalateDueCases(NOW)).isZero();
        assertThat(due.getStatus()).isEqualTo(CashAdvanceRecoveryCase.NOTICE_SENT);
    }

    @Test
    void dueCaseIsNotRaisedAgain_whenASalaryDeductionIsAlreadyOpen() {
        CashAdvanceRecoveryCase due = openCase(advance, NOW.minusHours(1));
        when(caseRepository.findByStatusAndDueAtLessThanEqual(CashAdvanceRecoveryCase.NOTICE_SENT, NOW))
                .thenReturn(List.of(due));
        when(caseRepository.findByIdForUpdate(due.getRecoveryCaseId())).thenReturn(Optional.of(due));
        when(cashAdvanceRepository.findByIdForUpdate(advanceId)).thenReturn(Optional.of(advance));
        when(salaryDeductionRequestRepository.findByAdvance_AdvanceIdAndStatusIn(eq(advanceId), anyCollection()))
                .thenReturn(List.of(com.expense_management_service.entity.SalaryDeductionRequest.builder()
                        .status(com.expense_management_service.entity.SalaryDeductionRequest.SENT_TO_PAYROLL).build()));

        assertThat(service.escalateDueCases(NOW)).isZero();
        verify(salaryDeductionService, never()).initiateForRecoveryCase(any(), any(), any(), any());
    }

    @Test
    void repaymentInFull_closesTheOpenNotice_withoutWaitingForTheScheduledRun() {
        CashAdvanceRecoveryCase open = openCase(advance, NOW.plusHours(5));
        when(caseRepository.findByAdvance_AdvanceIdAndStatusIn(advanceId, List.of(CashAdvanceRecoveryCase.NOTICE_SENT)))
                .thenReturn(List.of(open));
        when(caseRepository.findByIdForUpdate(open.getRecoveryCaseId())).thenReturn(Optional.of(open));

        int resolved = service.resolveNoticesIfSettled(advanceId, BigDecimal.ZERO, NOW);

        assertThat(resolved).isEqualTo(1);
        assertThat(open.getStatus()).isEqualTo(CashAdvanceRecoveryCase.RETURNED);
    }

    @Test
    void repaymentNotInFull_leavesTheNoticeOpen() {
        assertThat(service.resolveNoticesIfSettled(advanceId, new BigDecimal("10.00"), NOW)).isZero();
        verify(caseRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void dueCaseWhoseBalanceIsBackAtZero_becomesReturned_andNoReportIsQueued() {
        advance.setOutstandingBalance(BigDecimal.ZERO);
        CashAdvanceRecoveryCase due = openCase(advance, NOW.minusHours(1));
        when(caseRepository.findByStatusAndDueAtLessThanEqual(CashAdvanceRecoveryCase.NOTICE_SENT, NOW))
                .thenReturn(List.of(due));
        when(caseRepository.findByIdForUpdate(due.getRecoveryCaseId())).thenReturn(Optional.of(due));

        assertThat(service.escalateDueCases(NOW)).isZero();

        assertThat(due.getStatus()).isEqualTo(CashAdvanceRecoveryCase.RETURNED);
        assertThat(due.getResolvedAt()).isEqualTo(NOW);
        verify(outboundEmailRepository, never()).save(any());
    }

    @Test
    void caseNotYetDue_isNotEscalated() {
        when(caseRepository.findByStatusAndDueAtLessThanEqual(CashAdvanceRecoveryCase.NOTICE_SENT, NOW))
                .thenReturn(List.of());

        assertThat(service.escalateDueCases(NOW)).isZero();
        verify(outboundEmailRepository, never()).save(any());
    }

    @Test
    void withoutAPayrollEmail_theReportIsNotQueued_andTheCaseStaysOpen() {
        CashAdvanceRecoveryCase due = openCase(advance, NOW.minusHours(1));
        when(caseRepository.findByStatusAndDueAtLessThanEqual(CashAdvanceRecoveryCase.NOTICE_SENT, NOW))
                .thenReturn(List.of(due));
        when(caseRepository.findByIdForUpdate(due.getRecoveryCaseId())).thenReturn(Optional.of(due));

        assertThat(service.escalateDueCases(NOW)).isZero();

        assertThat(due.getStatus()).isEqualTo(CashAdvanceRecoveryCase.NOTICE_SENT);
        verify(outboundEmailRepository, never()).save(any());
    }

    @Test
    void aCaseAlreadySentToPayroll_isNotReportedAgain() {
        CashAdvanceRecoveryCase alreadySent = openCase(advance, NOW.minusHours(1));
        alreadySent.setStatus(CashAdvanceRecoveryCase.SENT_TO_PAYROLL);
        when(caseRepository.findByStatusAndDueAtLessThanEqual(CashAdvanceRecoveryCase.NOTICE_SENT, NOW))
                .thenReturn(List.of(alreadySent));
        when(caseRepository.findByIdForUpdate(alreadySent.getRecoveryCaseId())).thenReturn(Optional.of(alreadySent));

        assertThat(service.escalateDueCases(NOW)).isZero();
        verify(outboundEmailRepository, never()).save(any());
    }

    // ------------------------------------------------------------ outbox

    @Test
    void outbox_marksAMessageSent_whenTheTransportAcceptsIt() {
        OutboundEmail pending = pendingEmail();
        when(outboundEmailRepository.findByStatusOrderByCreatedAtAsc(eq(OutboundEmail.PENDING), any()))
                .thenReturn(List.of(pending));

        assertThat(outbox.dispatchPending()).isEqualTo(1);

        assertThat(pending.getStatus()).isEqualTo(OutboundEmail.SENT);
        assertThat(pending.getSentAt()).isNotNull();
        verify(emailTransport).send(eq("payroll@company.test"), anyString(), anyString());
    }

    @Test
    void outbox_keepsTheMessagePending_whenTheTransportRejectsIt() {
        OutboundEmail pending = pendingEmail();
        when(outboundEmailRepository.findByStatusOrderByCreatedAtAsc(eq(OutboundEmail.PENDING), any()))
                .thenReturn(List.of(pending));
        org.mockito.Mockito.doThrow(new EmailTransportException("smtp down")).when(emailTransport)
                .send(anyString(), anyString(), anyString());

        assertThat(outbox.dispatchPending()).isZero();

        assertThat(pending.getStatus()).isEqualTo(OutboundEmail.PENDING);
        assertThat(pending.getAttempts()).isEqualTo(1);
        assertThat(pending.getLastError()).isEqualTo("smtp down");
    }

    @Test
    void outbox_marksFailed_afterTheMaximumNumberOfAttempts() {
        OutboundEmail pending = pendingEmail();
        pending.setAttempts(EmailOutboxService.MAX_ATTEMPTS - 1);
        when(outboundEmailRepository.findByStatusOrderByCreatedAtAsc(eq(OutboundEmail.PENDING), any()))
                .thenReturn(List.of(pending));
        org.mockito.Mockito.doThrow(new EmailTransportException("smtp down")).when(emailTransport)
                .send(anyString(), anyString(), anyString());

        outbox.dispatchPending();

        assertThat(pending.getStatus()).isEqualTo(OutboundEmail.FAILED);
    }

    @Test
    void defaultTransport_neverReportsSuccess_soNoMessageIsMarkedSentByAccident() {
        var unconfigured = new com.expense_management_service.integration.email.UnconfiguredEmailTransport();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> unconfigured.send("a@b.test", "s", "b"))
                .isInstanceOf(EmailTransportException.class)
                .hasMessageContaining("No mail transport is configured");
    }

    private OutboundEmail pendingEmail() {
        return OutboundEmail.builder().outboundEmailId(UUID.randomUUID()).toAddress("payroll@company.test")
                .subject("Salary recovery report").body("body").emailType("CASH_ADVANCE_PAYROLL_REPORT")
                .status(OutboundEmail.PENDING).attempts(0).createdAt(NOW).build();
    }
}
