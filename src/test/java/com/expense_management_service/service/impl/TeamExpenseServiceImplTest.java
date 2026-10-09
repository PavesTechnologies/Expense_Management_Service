package com.expense_management_service.service.impl;

import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.ExpenseReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamExpenseServiceImplTest {

    private static final String MANAGER = "5100001";

    @Mock private EmployeeCacheRepository employeeCacheRepository;
    @Mock private ExpenseReportRepository expenseReportRepository;
    @Mock private ExpenseReportService expenseReportService;
    @Mock private CurrentUserService currentUserService;

    private TeamExpenseServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TeamExpenseServiceImpl(employeeCacheRepository, expenseReportRepository, expenseReportService, currentUserService);
        when(currentUserService.getEmployeeId()).thenReturn(MANAGER);
    }

    private static EmployeeCache member(String id, String first, String last) {
        return EmployeeCache.builder().employeeId(id).firstName(first).lastName(last).employmentStatus("Active")
                .managerEmployeeId(MANAGER).build();
    }

    private static ExpenseReport report(String employeeId, ReportStatus status, String amount) {
        return ExpenseReport.builder().employeeId(employeeId).reportStatus(status).totalAmount(new BigDecimal(amount))
                .submittedAt(LocalDateTime.of(2026, 9, 1, 10, 0)).build();
    }

    @Test
    void summary_bucketsEachMembersReports_andExcludesRejectedFromAmounts() {
        when(employeeCacheRepository.findByManagerEmployeeId(MANAGER))
                .thenReturn(List.of(member("5100002", "Asha", "Rao"), member("5100003", "Ben", "Lee")));
        ExpenseReport paid = report("5100002", ReportStatus.APPROVED, "300");
        paid.setPaymentRoutingStatus(PaymentRoutingStatus.PAYMENT_COMPLETED);
        when(expenseReportRepository.findByEmployeeIdInAndReportStatusNot(any(), eq(ReportStatus.DRAFT))).thenReturn(List.of(
                report("5100002", ReportStatus.PENDING_APPROVAL, "100"),
                report("5100002", ReportStatus.APPROVED, "200"),
                paid,
                report("5100002", ReportStatus.REJECTED, "999")));

        var summary = service.getSummary();

        assertThat(summary.memberCount()).isEqualTo(2);
        assertThat(summary.reportCount()).isEqualTo(4);
        var asha = summary.members().get(0);
        assertThat(asha.name()).isEqualTo("Asha Rao");
        assertThat(asha.inProgressCount()).isEqualTo(1);
        assertThat(asha.rejectedCount()).isEqualTo(1);
        assertThat(asha.inProgressAmount()).isEqualByComparingTo("100");
        assertThat(asha.approvedAmount()).isEqualByComparingTo("200");
        assertThat(asha.reimbursedAmount()).isEqualByComparingTo("300");
        assertThat(asha.claimedAmount()).isEqualByComparingTo("600");
        var ben = summary.members().get(1);
        assertThat(ben.reportCount()).isZero();
        assertThat(ben.claimedAmount()).isEqualByComparingTo("0");
        assertThat(summary.claimedAmount()).isEqualByComparingTo("600");
    }

    @Test
    void summary_withNoDirectReports_skipsTheReportQuery() {
        when(employeeCacheRepository.findByManagerEmployeeId(MANAGER)).thenReturn(List.of());

        var summary = service.getSummary();

        assertThat(summary.memberCount()).isZero();
        verifyNoInteractions(expenseReportRepository);
    }

    @Test
    void getReports_rejectsAnEmployeeOutsideTheTeam() {
        when(employeeCacheRepository.findByManagerEmployeeId(MANAGER)).thenReturn(List.of(member("5100002", "Asha", "Rao")));

        assertThatThrownBy(() -> service.getReports(PageRequest.of(0, 10), null, null, "5100999"))
                .isInstanceOf(AccessDeniedException.class);
        verify(expenseReportService, never()).getSubmittedForEmployees(any(), any(), any(), any());
    }

    @Test
    void getReports_narrowsToOneTeamMember_whenRequested() {
        when(employeeCacheRepository.findByManagerEmployeeId(MANAGER))
                .thenReturn(List.of(member("5100002", "Asha", "Rao"), member("5100003", "Ben", "Lee")));

        service.getReports(PageRequest.of(0, 10), "APPROVED", "trip", "5100003");

        verify(expenseReportService).getSubmittedForEmployees(eq(Set.of("5100003")), any(), eq("APPROVED"), eq("trip"));
    }

    @Test
    void team_neverIncludesTheManagerThemself() {
        when(employeeCacheRepository.findByManagerEmployeeId(MANAGER))
                .thenReturn(List.of(member(MANAGER, "Self", "Loop"), member("5100002", "Asha", "Rao")));

        assertThat(service.getMembers()).extracting("employeeId").containsExactly("5100002");
    }
}
