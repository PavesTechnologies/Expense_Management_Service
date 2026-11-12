package com.expense_management_service.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.expense_management_service.dto.response.ExpenseSummaryReportResponse;
import com.expense_management_service.dto.response.ExpenseSummaryReportResponse.CashAdvances;
import com.expense_management_service.dto.response.ExpenseSummaryReportResponse.Reimbursements;
import com.expense_management_service.dto.response.ExpenseSummaryReportResponse.Row;
import com.expense_management_service.dto.response.ExpenseSummaryReportResponse.Totals;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.ExpenseReportingService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregates in memory over one row per line item for the range - the same approach as
 * DashboardServiceImpl, and bounded by the range cap below.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExpenseReportingServiceImpl implements ExpenseReportingService {

    static final String SCOPE_ORGANIZATION = "ORGANIZATION";
    static final String SCOPE_TEAM = "TEAM";
    /** Roles that see organization-wide figures; anyone else allowed in by the controller sees their team. */
    private static final Set<String> ORG_WIDE_ROLES = Set.of("ADMIN", "SUPER_ADMIN", "FINANCE", "FINANCE_EXECUTIVE");
    private static final Set<ReportStatus> EXCLUDED = EnumSet.of(ReportStatus.DRAFT, ReportStatus.CANCELLED, ReportStatus.REJECTED);
    /** Advance statuses where money is with the employee and not yet settled - CashAdvanceServiceImpl's "active advances" set. */
    private static final Set<String> ADVANCE_OUTSTANDING = Set.of("DISBURSED", "IN_PROGRESS", "RECONCILIATION_PENDING",
            "SUBMITTED_FOR_REVIEW", "UNDER_REVIEW", "SETTLEMENT_PENDING", "PARTIALLY_SETTLED", "PARTIALLY_ADJUSTED", "OVERDUE");
    /** Advances that never went ahead - shown by status, but not part of the requested total. */
    private static final Set<String> ADVANCE_NOT_PROCEEDED = Set.of("CANCELLED", "REJECTED");
    private static final int MAX_RANGE_MONTHS = 36;
    private static final int TOP_EMPLOYEES = 50;
    private static final String UNASSIGNED = "unassigned";

    private final CurrentUserService currentUserService;
    private final EmployeeCacheRepository employeeCacheRepository;

    @PersistenceContext
    private EntityManager em;

    @Value("${exchange.rate.base-currency:}")
    private String baseCurrencyCode;

    @Override
    public ExpenseSummaryReportResponse summary(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : YearMonth.from(end).minusMonths(11).atDay(1);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from cannot be after to");
        }
        if (YearMonth.from(start).plusMonths(MAX_RANGE_MONTHS).isBefore(YearMonth.from(end))) {
            throw new IllegalArgumentException("The date range cannot be longer than " + MAX_RANGE_MONTHS + " months");
        }

        CurrentUser caller = currentUserService.getCurrentUser();
        boolean orgWide = caller.roles() != null && caller.roles().stream()
                .anyMatch(r -> ORG_WIDE_ROLES.contains(r.toUpperCase(Locale.ROOT)));
        Set<String> scopeIds = orgWide ? null : teamIds(caller.employeeId());
        String scope = orgWide ? SCOPE_ORGANIZATION : SCOPE_TEAM;
        if (scopeIds != null && scopeIds.isEmpty()) {
            return empty(scope, start, end);
        }

        List<Line> lines = lines(start, end, scopeIds);
        Map<String, EmployeeCache> employees = employees(lines.stream().map(Line::employeeId).collect(Collectors.toSet()));
        List<Allocation> allocations = allocations(lines, start, end, scopeIds, employees);

        BigDecimal spend = sum(lines);
        long reportCount = lines.stream().map(Line::reportId).distinct().count();
        long employeeCount = lines.stream().map(Line::employeeId).distinct().count();
        Totals totals = new Totals(spend, lines.size(), reportCount, employeeCount,
                reportCount == 0 ? BigDecimal.ZERO : spend.divide(BigDecimal.valueOf(reportCount), 2, RoundingMode.HALF_UP));

        return new ExpenseSummaryReportResponse(scope, baseCurrencyCode, start, end, totals,
                byMonth(lines, start, end),
                group(lines, l -> l.categoryName() != null ? l.categoryName() : "Uncategorized",
                        l -> l.categoryName() != null ? l.categoryName() : "Uncategorized"),
                byCostCenter(allocations),
                byDepartment(allocations),
                byEmployee(lines, employees),
                byStatus(lines),
                cashAdvances(start, end, scopeIds),
                reimbursements(start, end, scopeIds));
    }

    /**
     * One line item in range: (lineItemId, expenseDate, amount, baseAmount, category, the line's cost
     * center or else its report's, report, and that cost center's department).
     */
    record Line(UUID lineItemId, LocalDate expenseDate, BigDecimal amount, BigDecimal baseAmount, String categoryName,
                UUID costCenterId, String costCenterName, UUID reportId, String employeeId, ReportStatus reportStatus,
                UUID costCenterDepartmentUuid) {
    }

    /**
     * Where a line's spend is charged, in base currency: the whole line for an unsplit line, or one
     * row per active split. {@code departmentKey} is the cost center's department - the business
     * purpose's department, not the payer's - falling back to the payer's department only when the
     * cost center has none.
     */
    record Allocation(UUID lineItemId, String costCenterKey, String costCenterName, String departmentKey, BigDecimal amount) {
    }

    private List<Line> lines(LocalDate start, LocalDate end, Set<String> scopeIds) {
        TypedQuery<Object[]> query = em.createQuery("""
                        select l.lineItemId, l.expenseDate, l.amount, l.baseAmount, c.categoryName,
                               coalesce(lcc.costCenterId, rcc.costCenterId), coalesce(lcc.costCenterName, rcc.costCenterName),
                               r.reportId, r.employeeId, r.reportStatus,
                               case when lcc.costCenterId is not null then lcc.departmentUuid else rcc.departmentUuid end
                        from ExpenseLineItem l join l.report r left join l.category c
                             left join l.costCenter lcc left join r.costCenter rcc
                        where l.expenseDate between :start and :end and r.reportStatus not in :excluded
                        """ + (scopeIds != null ? " and r.employeeId in :ids" : ""), Object[].class)
                .setParameter("start", start).setParameter("end", end).setParameter("excluded", EXCLUDED);
        if (scopeIds != null) query.setParameter("ids", scopeIds);
        return query.getResultList().stream()
                .map(row -> new Line((UUID) row[0], (LocalDate) row[1], amount(row[2]), amount(row[3]), (String) row[4],
                        (UUID) row[5], (String) row[6], (UUID) row[7], (String) row[8], (ReportStatus) row[9], (UUID) row[10]))
                .toList();
    }

    private List<Row> byMonth(List<Line> lines, LocalDate start, LocalDate end) {
        Map<YearMonth, long[]> counts = new LinkedHashMap<>();
        Map<YearMonth, BigDecimal> amounts = new LinkedHashMap<>();
        for (YearMonth m = YearMonth.from(start); !m.isAfter(YearMonth.from(end)); m = m.plusMonths(1)) {
            counts.put(m, new long[1]);
            amounts.put(m, BigDecimal.ZERO);
        }
        lines.forEach(l -> {
            YearMonth m = YearMonth.from(l.expenseDate());
            if (!counts.containsKey(m)) return;
            counts.get(m)[0]++;
            amounts.merge(m, l.baseAmount(), BigDecimal::add);
        });
        return counts.keySet().stream()
                .map(m -> new Row(m.toString(), m.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + m.getYear(),
                        counts.get(m)[0], amounts.get(m)))
                .toList();
    }

    /**
     * Split lines contribute each split's {@code allocatedAmount} - already in base currency (splits
     * are sized from the line's baseAmount) - so it is used as-is, never rescaled by the line's rate.
     */
    private List<Allocation> allocations(List<Line> lines, LocalDate start, LocalDate end, Set<String> scopeIds,
                                         Map<String, EmployeeCache> employees) {
        TypedQuery<Object[]> query = em.createQuery("""
                        select l.lineItemId, cc.costCenterId, cc.costCenterName, s.allocatedAmount, cc.departmentUuid, r.employeeId
                        from ExpenseSplit s join s.lineItem l join l.report r join s.costCenter cc
                        where s.removedAt is null and l.expenseDate between :start and :end and r.reportStatus not in :excluded
                        """ + (scopeIds != null ? " and r.employeeId in :ids" : ""), Object[].class)
                .setParameter("start", start).setParameter("end", end).setParameter("excluded", EXCLUDED);
        if (scopeIds != null) query.setParameter("ids", scopeIds);

        List<Allocation> allocations = new ArrayList<>();
        Set<UUID> splitLineIds = new HashSet<>();
        for (Object[] s : query.getResultList()) {
            splitLineIds.add((UUID) s[0]);
            allocations.add(new Allocation((UUID) s[0], s[1].toString(), (String) s[2],
                    departmentKey((UUID) s[4], employees.get((String) s[5])), amount(s[3])));
        }
        for (Line l : lines) {
            if (splitLineIds.contains(l.lineItemId())) continue;
            allocations.add(new Allocation(l.lineItemId(),
                    l.costCenterId() != null ? l.costCenterId().toString() : UNASSIGNED,
                    l.costCenterName() != null ? l.costCenterName() : "No cost center",
                    departmentKey(l.costCenterDepartmentUuid(), employees.get(l.employeeId())),
                    l.baseAmount()));
        }
        return allocations;
    }

    /** Unsplit lines count under their own (or their report's) cost center; split lines under each split's cost center by its allocated share. */
    private List<Row> byCostCenter(List<Allocation> allocations) {
        Map<String, String> labels = new HashMap<>();
        allocations.forEach(a -> labels.put(a.costCenterKey(), a.costCenterName()));
        return groupAllocations(allocations, Allocation::costCenterKey, labels::get);
    }

    /** Each share counts under its cost center's department, so a bill split Engineering/HR shows under both. */
    private List<Row> byDepartment(List<Allocation> allocations) {
        return groupAllocations(allocations, Allocation::departmentKey, key -> key);
    }

    /** Counts distinct line items per group (a line split twice within one group counts once). */
    private List<Row> groupAllocations(List<Allocation> allocations, Function<Allocation, String> key,
                                       Function<String, String> label) {
        Map<String, Set<UUID>> lineIds = new HashMap<>();
        Map<String, BigDecimal> amounts = new HashMap<>();
        for (Allocation a : allocations) {
            String k = key.apply(a);
            lineIds.computeIfAbsent(k, x -> new HashSet<>()).add(a.lineItemId());
            amounts.merge(k, a.amount(), BigDecimal::add);
        }
        return sorted(amounts.keySet().stream()
                .map(k -> new Row(k, label.apply(k), lineIds.get(k).size(), amounts.get(k)))
                .toList());
    }

    private static String departmentKey(UUID costCenterDepartment, EmployeeCache payer) {
        return costCenterDepartment != null ? costCenterDepartment.toString() : departmentOf(payer);
    }

    private List<Row> byEmployee(List<Line> lines, Map<String, EmployeeCache> employees) {
        List<Row> rows = group(lines, Line::employeeId, l -> {
            EmployeeCache e = employees.get(l.employeeId());
            return e != null ? EmployeeDirectoryServiceImpl.toResponse(e).name() : l.employeeId();
        });
        return rows.size() > TOP_EMPLOYEES ? rows.subList(0, TOP_EMPLOYEES) : rows;
    }

    /** Count is reports (not line items) here, since status is a report-level fact. */
    private List<Row> byStatus(List<Line> lines) {
        Map<ReportStatus, Set<UUID>> reports = new HashMap<>();
        Map<ReportStatus, BigDecimal> amounts = new HashMap<>();
        lines.forEach(l -> {
            reports.computeIfAbsent(l.reportStatus(), k -> new HashSet<>()).add(l.reportId());
            amounts.merge(l.reportStatus(), l.baseAmount(), BigDecimal::add);
        });
        return sorted(reports.keySet().stream()
                .map(s -> new Row(s.name(), s.name(), reports.get(s).size(), amounts.get(s))).toList());
    }

    private CashAdvances cashAdvances(LocalDate start, LocalDate end, Set<String> scopeIds) {
        TypedQuery<Object[]> query = em.createQuery("""
                        select a.status, a.amount, a.baseAmount, a.outstandingBalance from CashAdvance a
                        where a.createdAt >= :start and a.createdAt < :end and a.status <> 'DRAFT'
                        """ + (scopeIds != null ? " and a.employeeId in :ids" : ""), Object[].class)
                .setParameter("start", start.atStartOfDay()).setParameter("end", end.plusDays(1).atStartOfDay());
        if (scopeIds != null) query.setParameter("ids", scopeIds);
        List<Object[]> rows = query.getResultList();

        Map<String, Long> counts = new HashMap<>();
        Map<String, BigDecimal> amounts = new HashMap<>();
        BigDecimal requested = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        for (Object[] row : rows) {
            String status = row[0] != null ? (String) row[0] : "UNKNOWN";
            BigDecimal amount = amount(row[1]);
            BigDecimal base = row[2] != null ? amount(row[2]) : amount;
            counts.merge(status, 1L, Long::sum);
            amounts.merge(status, base, BigDecimal::add);
            if (!ADVANCE_NOT_PROCEEDED.contains(status.toUpperCase(Locale.ROOT))) {
                requested = requested.add(base);
            }
            // outstandingBalance is in the advance's own currency; convert at the advance's own rate.
            BigDecimal owed = amount(row[3]);
            if (owed.signum() > 0 && ADVANCE_OUTSTANDING.contains(status.toUpperCase(Locale.ROOT))) {
                outstanding = outstanding.add(amount.signum() == 0 ? owed
                        : owed.multiply(base).divide(amount, 4, RoundingMode.HALF_UP));
            }
        }
        return new CashAdvances(rows.size(), requested, outstanding,
                sorted(counts.keySet().stream().map(s -> new Row(s, s, counts.get(s), amounts.get(s))).toList()));
    }

    private Reimbursements reimbursements(LocalDate start, LocalDate end, Set<String> scopeIds) {
        String scopeClause = scopeIds != null ? " and r.employeeId in :ids" : "";
        TypedQuery<Object[]> paid = em.createQuery("""
                        select count(r), coalesce(sum(coalesce(r.paidAmount, r.reimbursableAmount, r.totalAmount)), 0) from ExpenseReport r
                        where r.paymentRoutingStatus = :completed and r.paymentCompletedAt >= :start and r.paymentCompletedAt < :end
                        """ + scopeClause, Object[].class)
                .setParameter("completed", PaymentRoutingStatus.PAYMENT_COMPLETED)
                .setParameter("start", start.atStartOfDay()).setParameter("end", end.plusDays(1).atStartOfDay());
        TypedQuery<Object[]> awaiting = em.createQuery("""
                        select count(r), coalesce(sum(coalesce(r.reimbursableAmount, r.totalAmount)), 0) from ExpenseReport r
                        where r.paymentRoutingStatus = :approved
                        """ + scopeClause, Object[].class)
                .setParameter("approved", PaymentRoutingStatus.APPROVED_FOR_PAYMENT);
        if (scopeIds != null) {
            paid.setParameter("ids", scopeIds);
            awaiting.setParameter("ids", scopeIds);
        }
        Object[] p = paid.getSingleResult();
        Object[] a = awaiting.getSingleResult();
        return new Reimbursements(((Number) p[0]).longValue(), amount(p[1]), ((Number) a[0]).longValue(), amount(a[1]));
    }

    private List<Row> group(List<Line> lines, Function<Line, String> key, Function<Line, String> label) {
        Map<String, String> labels = new HashMap<>();
        Map<String, Long> counts = new HashMap<>();
        Map<String, BigDecimal> amounts = new HashMap<>();
        lines.forEach(l -> {
            String k = key.apply(l);
            labels.putIfAbsent(k, label.apply(l));
            counts.merge(k, 1L, Long::sum);
            amounts.merge(k, l.baseAmount(), BigDecimal::add);
        });
        return sorted(labels.keySet().stream().map(k -> new Row(k, labels.get(k), counts.get(k), amounts.get(k))).toList());
    }

    private static List<Row> sorted(List<Row> rows) {
        return rows.stream().sorted(Comparator.comparing(Row::amount).reversed().thenComparing(Row::label)).toList();
    }

    private Set<String> teamIds(String me) {
        if (me == null || me.isBlank()) return Set.of();
        return employeeCacheRepository.findByManagerEmployeeId(me).stream()
                .map(EmployeeCache::getEmployeeId)
                .filter(id -> id != null && !id.isBlank() && !id.equals(me))
                .collect(Collectors.toSet());
    }

    private Map<String, EmployeeCache> employees(Set<String> ids) {
        if (ids.isEmpty()) return Map.of();
        return employeeCacheRepository.findByEmployeeIdIn(ids).stream()
                .collect(Collectors.toMap(EmployeeCache::getEmployeeId, e -> e, (a, b) -> a));
    }

    private static String departmentOf(EmployeeCache e) {
        return e != null && e.getDepartmentUuid() != null && !e.getDepartmentUuid().isBlank() ? e.getDepartmentUuid() : UNASSIGNED;
    }

    private static BigDecimal sum(List<Line> lines) {
        return lines.stream().map(Line::baseAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal amount(Object value) {
        return value instanceof BigDecimal b ? b : value instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO;
    }

    private ExpenseSummaryReportResponse empty(String scope, LocalDate start, LocalDate end) {
        return new ExpenseSummaryReportResponse(scope, baseCurrencyCode, start, end,
                new Totals(BigDecimal.ZERO, 0, 0, 0, BigDecimal.ZERO), byMonth(List.of(), start, end),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                new CashAdvances(0, BigDecimal.ZERO, BigDecimal.ZERO, List.of()),
                new Reimbursements(0, BigDecimal.ZERO, 0, BigDecimal.ZERO));
    }
}
