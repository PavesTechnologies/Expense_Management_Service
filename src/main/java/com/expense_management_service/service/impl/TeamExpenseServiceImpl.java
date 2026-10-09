package com.expense_management_service.service.impl;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.expense_management_service.dto.response.EmployeeSummaryResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.dto.response.TeamExpenseSummaryResponse;
import com.expense_management_service.dto.response.TeamExpenseSummaryResponse.Member;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.ExpenseReportService;
import com.expense_management_service.service.TeamExpenseService;
import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Team" means direct reports (EmployeeCache.managerEmployeeId = caller), the same reporting line
 * approval flows use for an employee's manager. Former employees stay listed while they still
 * report to the caller in the cache, so their past claims remain visible.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamExpenseServiceImpl implements TeamExpenseService {

    /** With approvers/Finance, or back with the employee mid-review - not yet a final outcome. */
    static final Set<ReportStatus> IN_PROGRESS = EnumSet.of(ReportStatus.SUBMITTED, ReportStatus.PENDING_APPROVAL,
            ReportStatus.PENDING_FINANCE_VERIFICATION, ReportStatus.AWAITING_CORRECTION, ReportStatus.QUERY_RAISED,
            ReportStatus.POLICY_REJECTED);
    /** Counted, but never part of any claimed amount. */
    static final Set<ReportStatus> NOT_CLAIMED = EnumSet.of(ReportStatus.REJECTED, ReportStatus.CANCELLED);

    private final EmployeeCacheRepository employeeCacheRepository;
    private final ExpenseReportRepository expenseReportRepository;
    private final ExpenseReportService expenseReportService;
    private final CurrentUserService currentUserService;

    @Value("${exchange.rate.base-currency:}")
    private String baseCurrencyCode;

    @Override
    public List<EmployeeSummaryResponse> getMembers() {
        return team().stream()
                .map(EmployeeDirectoryServiceImpl::toResponse)
                .sorted(Comparator.comparing(EmployeeSummaryResponse::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public PageResponse<ExpenseReportResponse> getReports(Pageable pageable, String status, String search, String employeeId) {
        Set<String> teamIds = team().stream().map(EmployeeCache::getEmployeeId).collect(Collectors.toSet());
        if (employeeId != null && !employeeId.isBlank()) {
            if (!teamIds.contains(employeeId.trim())) {
                throw new AccessDeniedException("Employee " + employeeId + " is not in your team");
            }
            teamIds = Set.of(employeeId.trim());
        }
        return expenseReportService.getSubmittedForEmployees(teamIds, pageable, status, search);
    }

    @Override
    public TeamExpenseSummaryResponse getSummary() {
        List<EmployeeCache> members = team();
        Set<String> teamIds = members.stream().map(EmployeeCache::getEmployeeId).collect(Collectors.toSet());
        Map<String, List<ExpenseReport>> reportsByEmployee = teamIds.isEmpty() ? Map.of()
                : expenseReportRepository.findByEmployeeIdInAndReportStatusNot(teamIds, ReportStatus.DRAFT).stream()
                        .collect(Collectors.groupingBy(ExpenseReport::getEmployeeId));

        List<Member> rows = members.stream()
                .map(m -> memberSummary(m, reportsByEmployee.getOrDefault(m.getEmployeeId(), List.of())))
                .sorted(Comparator.comparing(Member::claimedAmount).reversed()
                        .thenComparing(Member::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        return new TeamExpenseSummaryResponse(baseCurrencyCode, rows.size(),
                rows.stream().mapToLong(Member::reportCount).sum(),
                total(rows, Member::claimedAmount),
                total(rows, Member::inProgressAmount),
                total(rows, Member::approvedAmount),
                total(rows, Member::reimbursedAmount),
                rows);
    }

    private Member memberSummary(EmployeeCache member, List<ExpenseReport> reports) {
        EmployeeSummaryResponse who = EmployeeDirectoryServiceImpl.toResponse(member);
        List<ExpenseReport> claimed = reports.stream().filter(r -> !NOT_CLAIMED.contains(r.getReportStatus())).toList();
        List<ExpenseReport> inProgress = claimed.stream().filter(r -> IN_PROGRESS.contains(r.getReportStatus())).toList();
        List<ExpenseReport> reimbursed = claimed.stream().filter(TeamExpenseServiceImpl::isReimbursed).toList();
        List<ExpenseReport> approved = claimed.stream()
                .filter(r -> !IN_PROGRESS.contains(r.getReportStatus()) && !isReimbursed(r)).toList();
        return new Member(who.employeeId(), who.name(), who.email(), member.getEmploymentStatus(),
                reports.size(), inProgress.size(),
                reports.stream().filter(r -> r.getReportStatus() == ReportStatus.REJECTED).count(),
                sum(claimed), sum(inProgress), sum(approved), sum(reimbursed),
                reports.stream().map(ExpenseReport::getSubmittedAt).filter(Objects::nonNull)
                        .max(Comparator.naturalOrder()).orElse(null));
    }

    private static boolean isReimbursed(ExpenseReport r) {
        return r.getReportStatus() == ReportStatus.REIMBURSED
                || r.getPaymentRoutingStatus() == PaymentRoutingStatus.PAYMENT_COMPLETED;
    }

    private static BigDecimal sum(List<ExpenseReport> reports) {
        return reports.stream().map(ExpenseReport::getTotalAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal total(List<Member> rows, Function<Member, BigDecimal> amount) {
        return rows.stream().map(amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<EmployeeCache> team() {
        String me = currentUserService.getEmployeeId();
        if (me == null || me.isBlank()) {
            return List.of();
        }
        return employeeCacheRepository.findByManagerEmployeeId(me).stream()
                .filter(e -> e.getEmployeeId() != null && !e.getEmployeeId().isBlank() && !e.getEmployeeId().equals(me))
                .toList();
    }
}
