package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.DeadlineOverrideRequest;
import com.expense_management_service.dto.request.DeadlineOverrideRequest.DeadlineOverrideAction;
import com.expense_management_service.dto.response.DeadlineOverrideResponse;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpenseDeadlineOverrideServiceTest {

    private static final String FINANCE = "FIN001";
    private static final String OWNER = "EMP001";
    private static final String GOOD_REASON = "Client visit delayed the receipts by a month";

    @Mock
    private ExpenseReportRepository expenseReportRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private CurrentUserService currentUserService;

    private ExpenseDeadlineOverrideService service;
    private UUID reportId;
    private ExpenseReport report;

    @BeforeEach
    void setUp() {
        service = new ExpenseDeadlineOverrideService(expenseReportRepository, auditLogRepository, currentUserService);
        ReflectionTestUtils.setField(service, "submissionDeadlineDays", 30);
        reportId = UUID.randomUUID();
        report = ExpenseReport.builder()
                .reportId(reportId)
                .employeeId(OWNER)
                .reportStatus(ReportStatus.DRAFT)
                .title("Client visit")
                .tripEndDate(LocalDate.now().minusDays(10))
                .build();
        lenient().when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        lenient().when(currentUserService.getEmployeeId()).thenReturn(FINANCE);
    }

    private DeadlineOverrideRequest extend(LocalDate until, String reason) {
        return new DeadlineOverrideRequest(DeadlineOverrideAction.EXTEND, until, reason);
    }

    private DeadlineOverrideRequest reopen(String reason) {
        return new DeadlineOverrideRequest(DeadlineOverrideAction.REOPEN, null, reason);
    }

    @Test
    void extend_movesTheDeadline_andWritesAnAuditRowWithTheReason() {
        LocalDate until = LocalDate.now().plusDays(60);

        DeadlineOverrideResponse response = service.apply(reportId, extend(until, GOOD_REASON));

        assertThat(report.getExpenseDeadlineExtendedUntil()).isEqualTo(until);
        assertThat(response.submissionDeadline()).isEqualTo(until);
        assertThat(response.performedBy()).isEqualTo(FINANCE);
        verify(expenseReportRepository).save(report);

        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("DEADLINE_EXTENDED");
        assertThat(audit.getValue().getReason()).isEqualTo(GOOD_REASON);
        assertThat(audit.getValue().getPerformedBy()).isEqualTo(FINANCE);
        assertThat(audit.getValue().getEntityId()).isEqualTo(reportId);
    }

    @Test
    void reopen_liftsTheCheck_andReportsNoDeadline() {
        DeadlineOverrideResponse response = service.apply(reportId, reopen(GOOD_REASON));

        assertThat(report.isExpenseDeadlineReopened()).isTrue();
        assertThat(response.reopened()).isTrue();
        assertThat(response.submissionDeadline()).isNull();

        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("DEADLINE_REOPENED");
        assertThat(audit.getValue().getReason()).isEqualTo(GOOD_REASON);
    }

    @Test
    void advanceLinkedReport_extendsFromItsDisbursementDate() {
        report.setTripEndDate(LocalDate.now().minusDays(200));
        report.setCashAdvance(CashAdvance.builder().advanceId(UUID.randomUUID())
                .disbursedAt(LocalDateTime.now().minusDays(5)).build());

        // Disbursed 5 days ago: the computed deadline is today + 25, so the extension must go past that.
        DeadlineOverrideResponse response = service.apply(reportId, extend(LocalDate.now().plusDays(60), GOOD_REASON));

        assertThat(response.submissionDeadline()).isEqualTo(LocalDate.now().plusDays(60));
    }

    @Test
    void rejectsAReasonShorterThanTenCharacters_andChangesNothing() {
        assertThatThrownBy(() -> service.apply(reportId, extend(LocalDate.now().plusDays(60), "too short")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 10 characters");
        assertThat(report.getExpenseDeadlineExtendedUntil()).isNull();
        verify(expenseReportRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void rejectsABlankReason() {
        assertThatThrownBy(() -> service.apply(reportId, reopen("          ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(report.isExpenseDeadlineReopened()).isFalse();
    }

    @Test
    void rejectsAnOverrideOfTheCallersOwnReport() {
        when(currentUserService.getEmployeeId()).thenReturn(OWNER);

        assertThatThrownBy(() -> service.apply(reportId, extend(LocalDate.now().plusDays(60), GOOD_REASON)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("your own report");
        verify(expenseReportRepository, never()).save(any());
    }

    @Test
    void rejectsAReportThatIsAlreadySubmitted() {
        report.setReportStatus(ReportStatus.PENDING_APPROVAL);

        assertThatThrownBy(() -> service.apply(reportId, extend(LocalDate.now().plusDays(60), GOOD_REASON)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("still in draft");
        verify(expenseReportRepository, never()).save(any());
    }

    @Test
    void rejectsAnExtensionInThePast() {
        assertThatThrownBy(() -> service.apply(reportId, extend(LocalDate.now().minusDays(1), GOOD_REASON)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("The extended deadline cannot be in the past.");
    }

    @Test
    void rejectsAnExtensionThatShortensTheCurrentDeadline() {
        // Trip ended 10 days ago, so the deadline is today + 20. Moving it to today + 5 would shorten it.
        assertThatThrownBy(() -> service.apply(reportId, extend(LocalDate.now().plusDays(5), GOOD_REASON)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("cannot shorten the current deadline");
        verify(expenseReportRepository, never()).save(any());
    }

    @Test
    void rejectsAnExtensionWithoutADate() {
        assertThatThrownBy(() -> service.apply(reportId, extend(null, GOOD_REASON)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Enter the new submission date");
    }

    @Test
    void rejectsAReopenThatAlsoCarriesADate() {
        assertThatThrownBy(() -> service.apply(reportId,
                new DeadlineOverrideRequest(DeadlineOverrideAction.REOPEN, LocalDate.now().plusDays(3), GOOD_REASON)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A reopen does not take a date.");
    }

    @Test
    void reportsNotFound_whenTheReportDoesNotExist() {
        UUID missing = UUID.randomUUID();
        when(expenseReportRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.apply(missing, reopen(GOOD_REASON)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
