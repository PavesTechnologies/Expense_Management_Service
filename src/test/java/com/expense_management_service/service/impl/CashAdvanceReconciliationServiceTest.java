package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceReconciliationLine;
import com.expense_management_service.entity.CashAdvanceReconciliationRun;
import com.expense_management_service.repository.CashAdvanceAdjustmentRepository;
import com.expense_management_service.repository.CashAdvanceCarryForwardRepository;
import com.expense_management_service.repository.CashAdvanceReconciliationLineRepository;
import com.expense_management_service.repository.CashAdvanceReconciliationRunRepository;
import com.expense_management_service.repository.CashAdvanceRepaymentRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.security.CurrentUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CashAdvanceReconciliationServiceTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 10, 23);
    private static final String CREATOR = "FIN001";
    private static final String OTHER_FINANCE = "FIN002";

    @Mock private CashAdvanceRepository cashAdvanceRepository;
    @Mock private CashAdvanceCarryForwardRepository carryForwardRepository;
    @Mock private CashAdvanceRepaymentRepository repaymentRepository;
    @Mock private CashAdvanceAdjustmentRepository adjustmentRepository;
    @Mock private CashAdvanceReconciliationRunRepository runRepository;
    @Mock private CashAdvanceReconciliationLineRepository lineRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private CurrentUserService currentUserService;

    private CashAdvanceReconciliationService service;
    private CashAdvance matching;
    private CashAdvance mismatched;

    @BeforeEach
    void setUp() {
        service = new CashAdvanceReconciliationService(cashAdvanceRepository, carryForwardRepository, repaymentRepository,
                adjustmentRepository, runRepository, lineRepository, new CashAdvanceBalanceLedger(auditLogRepository),
                currentUserService);
        ReflectionTestUtils.setField(service, "financeYearStartMonth", 4);

        matching = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId("EMP001")
                .amount(new BigDecimal("1000")).outstandingBalance(new BigDecimal("1000"))
                .status("DISBURSED").disbursedAt(LocalDateTime.of(2026, 9, 1, 9, 0)).build();
        mismatched = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId("EMP002")
                .amount(new BigDecimal("1000")).outstandingBalance(new BigDecimal("400"))
                .status("DISBURSED").disbursedAt(LocalDateTime.of(2026, 9, 1, 9, 0)).build();

        lenient().when(cashAdvanceRepository.findAll()).thenReturn(List.of(matching, mismatched));
        lenient().when(carryForwardRepository.findByStatusIn(any())).thenReturn(new ArrayList<>());
        lenient().when(repaymentRepository.findAll()).thenReturn(new ArrayList<>());
        lenient().when(adjustmentRepository.findAll()).thenReturn(new ArrayList<>());
        lenient().when(runRepository.save(any(CashAdvanceReconciliationRun.class))).thenAnswer(inv -> {
            CashAdvanceReconciliationRun run = inv.getArgument(0);
            if (run.getReconciliationRunId() == null) run.setReconciliationRunId(UUID.randomUUID());
            return run;
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void actAs(String employeeId, String... roles) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(employeeId, null, authorities));
        lenient().when(currentUserService.getEmployeeId()).thenReturn(employeeId);
    }

    private CashAdvanceReconciliationRun draftRun() {
        CashAdvanceReconciliationRun run = CashAdvanceReconciliationRun.builder()
                .reconciliationRunId(UUID.randomUUID())
                .periodType("MONTHLY").periodStart(LocalDate.of(2026, 10, 1)).periodEnd(LocalDate.of(2026, 10, 31))
                .status(CashAdvanceReconciliationRun.DRAFT).createdBy(CREATOR).createdAt(LocalDateTime.now())
                .build();
        CashAdvanceReconciliationLine flagged = CashAdvanceReconciliationLine.builder()
                .reconciliationLineId(UUID.randomUUID()).run(run).advance(mismatched).employeeId("EMP002")
                .expectedClosing(new BigDecimal("1000")).storedClosing(new BigDecimal("400"))
                .discrepancy(new BigDecimal("-600")).flagged(true).laterMovements(0).build();
        run.getLines().add(flagged);
        return run;
    }

    // ------------------------------------------------------------ snapshot and creation

    @Test
    void snapshot_isReadOnly_andFlagsOnlyTheMismatchedAdvance() {
        actAs(CREATOR, "FINANCE");

        var snapshot = service.snapshot("MONTHLY", ANCHOR);

        assertThat(snapshot.periodStart()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(snapshot.lines()).hasSize(2);
        assertThat(snapshot.lines().stream().filter(l -> l.flagged()).count()).isEqualTo(1);
        verify(runRepository, never()).save(any());
        verify(cashAdvanceRepository, never()).save(any());
    }

    @Test
    void snapshot_isRefusedToNonFinance() {
        actAs("EMP001", "GENERAL");

        assertThatThrownBy(() -> service.snapshot("MONTHLY", ANCHOR))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only Finance");
    }

    @Test
    void createRun_storesDraftLinesFromTheSnapshot_andNeverWritesAnAdvance() {
        actAs(CREATOR, "FINANCE");
        when(runRepository.findFirstByPeriodTypeAndPeriodStartAndStatusNot(eq("MONTHLY"), any(), eq("CLOSED")))
                .thenReturn(Optional.empty());

        var run = service.createRun("MONTHLY", ANCHOR);

        assertThat(run.status()).isEqualTo("DRAFT");
        assertThat(run.lines()).hasSize(2);
        assertThat(run.lines().stream().filter(l -> l.flagged()).count()).isEqualTo(1);
        verify(cashAdvanceRepository, never()).save(any());
    }

    @Test
    void createRun_isRefused_whenAnOpenRunAlreadyExistsForThePeriod() {
        actAs(CREATOR, "FINANCE");
        when(runRepository.findFirstByPeriodTypeAndPeriodStartAndStatusNot(eq("MONTHLY"), any(), eq("CLOSED")))
                .thenReturn(Optional.of(draftRun()));

        assertThatThrownBy(() -> service.createRun("MONTHLY", ANCHOR))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("open reconciliation run already exists");
    }

    @Test
    void financialYearPeriod_isRefused_whileTheStartMonthIsNotConfigured() {
        actAs(CREATOR, "FINANCE");
        ReflectionTestUtils.setField(service, "financeYearStartMonth", 0);

        assertThatThrownBy(() -> service.createRun("FINANCIAL_YEAR", ANCHOR))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("has not confirmed the financial-year start");
    }

    // ------------------------------------------------------------ notes and workflow

    @Test
    void resolvingAFlaggedLine_recordsANote_andDoesNotChangeAnyBalance() {
        actAs(OTHER_FINANCE, "FINANCE");
        CashAdvanceReconciliationRun run = draftRun();
        UUID lineId = run.getLines().get(0).getReconciliationLineId();
        when(runRepository.findById(run.getReconciliationRunId())).thenReturn(Optional.of(run));

        var view = service.resolveLine(run.getReconciliationRunId(), lineId, "Matched to the July repayment slip");

        assertThat(view.lines().get(0).resolutionNote()).isEqualTo("Matched to the July repayment slip");
        assertThat(view.lines().get(0).resolvedBy()).isEqualTo(OTHER_FINANCE);
        assertThat(mismatched.getOutstandingBalance()).isEqualByComparingTo("400");
        verify(cashAdvanceRepository, never()).save(any());
    }

    @Test
    void resolutionNoteTooShort_isRefused() {
        actAs(OTHER_FINANCE, "FINANCE");
        CashAdvanceReconciliationRun run = draftRun();
        UUID lineId = run.getLines().get(0).getReconciliationLineId();
        when(runRepository.findById(run.getReconciliationRunId())).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.resolveLine(run.getReconciliationRunId(), lineId, "ok"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 10 characters");
    }

    @Test
    void approval_isRefused_whileAFlaggedLineHasNoNote() {
        actAs(OTHER_FINANCE, "FINANCE");
        CashAdvanceReconciliationRun run = draftRun();
        run.setStatus(CashAdvanceReconciliationRun.SUBMITTED);
        run.setSubmittedBy(CREATOR);
        when(runRepository.findById(run.getReconciliationRunId())).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.approve(run.getReconciliationRunId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("need a resolution note");
    }

    @Test
    void approval_byTheCreator_isRefused() {
        actAs(CREATOR, "FINANCE");
        CashAdvanceReconciliationRun run = draftRun();
        run.setStatus(CashAdvanceReconciliationRun.SUBMITTED);
        run.setSubmittedBy(OTHER_FINANCE);
        run.getLines().get(0).setResolutionNote("Matched to the July repayment slip");
        when(runRepository.findById(run.getReconciliationRunId())).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.approve(run.getReconciliationRunId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("created or submitted");
    }

    @Test
    void fullWorkflow_draftSubmitApproveClose_endsInAClosedRunThatCannotBeChanged() {
        CashAdvanceReconciliationRun run = draftRun();
        UUID runId = run.getReconciliationRunId();
        UUID lineId = run.getLines().get(0).getReconciliationLineId();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        actAs(CREATOR, "FINANCE");
        assertThat(service.submit(runId).status()).isEqualTo("SUBMITTED");

        actAs(OTHER_FINANCE, "FINANCE");
        service.resolveLine(runId, lineId, "Matched to the July repayment slip");
        assertThat(service.approve(runId).status()).isEqualTo("APPROVED");
        assertThat(service.close(runId).status()).isEqualTo("CLOSED");

        // Once closed, the run cannot be changed: no further note and no status step.
        assertThatThrownBy(() -> service.resolveLine(runId, lineId, "A later attempt to change the closed run"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("can no longer change");
        assertThatThrownBy(() -> service.submit(runId)).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> service.close(runId)).isInstanceOf(BusinessRuleViolationException.class);
        assertThat(mismatched.getOutstandingBalance()).isEqualByComparingTo("400");
    }

    @Test
    void closing_isRefused_beforeApproval() {
        actAs(CREATOR, "FINANCE");
        CashAdvanceReconciliationRun run = draftRun();
        when(runRepository.findById(run.getReconciliationRunId())).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.close(run.getReconciliationRunId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only approved runs can be closed");
    }
}
