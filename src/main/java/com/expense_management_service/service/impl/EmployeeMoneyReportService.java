package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.EmployeeMoneyReportResponse;
import com.expense_management_service.dto.response.EmployeeMoneyReportResponse.Advances;
import com.expense_management_service.dto.response.EmployeeMoneyReportResponse.Budget;
import com.expense_management_service.dto.response.EmployeeMoneyReportResponse.Expenses;
import com.expense_management_service.dto.response.EmployeeMoneyReportResponse.Group;
import com.expense_management_service.dto.response.EmployeeMoneyReportResponse.Point;
import com.expense_management_service.dto.response.EmployeeMoneyReportResponse.Tax;
import com.expense_management_service.dto.response.EmployeeMoneyReportResponse.TaxReview;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceAdjustment;
import com.expense_management_service.entity.CashAdvanceRepayment;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.CostCenterBudget;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.BudgetEncumbranceStatus;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.enums.TaxValidationStatus;
import com.expense_management_service.repository.BudgetEncumbranceRepository;
import com.expense_management_service.repository.CashAdvanceAdjustmentRepository;
import com.expense_management_service.repository.CashAdvanceRepaymentRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds {@link EmployeeMoneyReportResponse}: every cash advance and expense report of every employee, each rupee in one
 * bucket by where it is now, summed per employee, per department (the employee's department in the employee cache) and
 * for the organization, plus tax on the claimed lines and the fiscal year's budget per department.
 * <p>
 * The buckets use the same rules as the employee dashboard: an approved report's non-reimbursable part is "covered by
 * advance" only when the report is linked to an advance; an advance's balance comes from {@link CashAdvanceBalanceCalculator};
 * repayments with method PAYROLL are salary deductions. Read-only; a few batched queries for the whole organization.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmployeeMoneyReportService {

    static final String UNASSIGNED = "unassigned";
    static final String TOTAL = "total";

    private static final Set<String> PENDING_ADVANCE = Set.of("SUBMITTED", "PENDING", "PENDING_APPROVAL",
            "PENDING_MANAGER_APPROVAL", "PENDING_COST_CENTER_APPROVAL", "PENDING_COST_CENTER", "PENDING_FINANCE_APPROVAL",
            "PENDING_FINANCE");
    private static final Set<String> NOT_LIVE_ADVANCE = Set.of("DRAFT", "REJECTED", "CANCELLED");

    @PersistenceContext
    private EntityManager em;

    private final CashAdvanceRepository cashAdvanceRepository;
    private final CashAdvanceAdjustmentRepository adjustmentRepository;
    private final CashAdvanceRepaymentRepository repaymentRepository;
    private final CashAdvanceBalanceCalculator balanceCalculator;
    private final EmployeeCacheRepository employeeCacheRepository;
    private final BudgetEncumbranceRepository encumbranceRepository;

    @Value("${exchange.rate.base-currency:INR}")
    private String baseCurrencyCode = "INR";

    public EmployeeMoneyReportResponse report(LocalDate from, LocalDate to) {
        LocalDateTime start = from == null ? null : from.atStartOfDay();
        LocalDateTime end = to == null ? null : to.plusDays(1).atStartOfDay();
        Map<String, Acc> byEmployee = new LinkedHashMap<>();

        // ---- expense reports (everything but drafts), with their line totals and tax in the base currency
        List<ExpenseReport> reports = em.createQuery(
                        "select r from ExpenseReport r where r.reportStatus <> :draft", ExpenseReport.class)
                .setParameter("draft", ReportStatus.DRAFT).getResultList().stream()
                .filter(r -> inRange(r.getSubmittedAt() != null ? r.getSubmittedAt() : r.getCreatedAt(), start, end))
                .toList();
        Map<UUID, BigDecimal[]> lines = lineTotals(reports.stream().map(ExpenseReport::getReportId).toList());
        Map<String, Acc> byCostCenter = new LinkedHashMap<>();
        Map<String, CostCenter> costCenters = new HashMap<>();
        // [claimed, tax, recoverable tax] per submit day, for the timeline.
        Map<LocalDate, BigDecimal[]> claimedOn = new HashMap<>();
        for (ExpenseReport r : reports) {
            BigDecimal[] t = lines.getOrDefault(r.getReportId(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            acc(byEmployee, r.getEmployeeId()).addReport(r, t[0], t[1], t[2], t[3]);
            costCenterAcc(byCostCenter, costCenters, r.getCostCenter()).addReport(r, t[0], t[1], t[2], t[3]);
            if (r.getReportStatus() != ReportStatus.CANCELLED) {
                LocalDateTime at = r.getSubmittedAt() != null ? r.getSubmittedAt() : r.getCreatedAt();
                if (at != null) claimedOn.merge(at.toLocalDate(), new BigDecimal[]{t[0], t[1], t[2]}, EmployeeMoneyReportService::addAll);
            }
        }

        // ---- cash advances (every live one), with their adjustments, repayments and balance
        List<CashAdvance> liveAdvances = cashAdvanceRepository.findAll().stream()
                .filter(a -> !"DRAFT".equalsIgnoreCase(a.getStatus()))
                .toList();
        List<CashAdvance> advances = liveAdvances.stream()
                .filter(a -> inRange(a.getCreatedAt(), start, end))
                .toList();
        // Timeline: cash paid out, on the day it was paid (an advance requested earlier but paid in the range counts).
        Map<LocalDate, BigDecimal> paidOutOn = new HashMap<>();
        Acc rates = new Acc(baseCurrencyCode);
        for (CashAdvance a : liveAdvances) {
            if (a.getDisbursedAt() == null || !inRange(a.getDisbursedAt(), start, end)) continue;
            if (NOT_LIVE_ADVANCE.contains(a.getStatus() == null ? "" : a.getStatus().toUpperCase())) continue;
            paidOutOn.merge(a.getDisbursedAt().toLocalDate(), rates.paidOutBase(a), BigDecimal::add);
        }
        List<UUID> ids = advances.stream().map(CashAdvance::getAdvanceId).toList();
        Map<UUID, List<CashAdvanceAdjustment>> adjustments = ids.isEmpty() ? Map.of()
                : adjustmentRepository.findByCashAdvance_AdvanceIdIn(ids).stream()
                .collect(Collectors.groupingBy(a -> a.getCashAdvance().getAdvanceId()));
        Map<UUID, List<CashAdvanceRepayment>> repayments = ids.isEmpty() ? Map.of()
                : repaymentRepository.findByCashAdvance_AdvanceIdIn(ids).stream()
                .collect(Collectors.groupingBy(p -> p.getCashAdvance().getAdvanceId()));
        List<CashAdvance> paidOut = advances.stream().filter(a -> a.getDisbursedAt() != null).toList();
        Map<UUID, BigDecimal> owed = paidOut.isEmpty() ? Map.of() : balanceCalculator.rawOutstandingFor(paidOut);
        for (CashAdvance a : advances) {
            List<CashAdvanceAdjustment> adj = adjustments.getOrDefault(a.getAdvanceId(), List.of());
            List<CashAdvanceRepayment> paid = repayments.getOrDefault(a.getAdvanceId(), List.of());
            acc(byEmployee, a.getEmployeeId()).addAdvance(a, adj, paid, owed.get(a.getAdvanceId()));
            costCenterAcc(byCostCenter, costCenters, a.getCostCenter()).addAdvance(a, adj, paid, owed.get(a.getAdvanceId()));
        }

        // ---- departments: from the employee cache
        Map<String, EmployeeCache> people = byEmployee.isEmpty() ? Map.of()
                : employeeCacheRepository.findByEmployeeIdIn(byEmployee.keySet()).stream()
                .collect(Collectors.toMap(EmployeeCache::getEmployeeId, e -> e, (x, y) -> x));
        Map<String, Acc> byDepartment = new LinkedHashMap<>();
        Acc total = new Acc(baseCurrencyCode);
        List<Group> employeeRows = new ArrayList<>();
        for (Map.Entry<String, Acc> e : byEmployee.entrySet()) {
            EmployeeCache person = people.get(e.getKey());
            String dept = person != null && person.getDepartmentUuid() != null && !person.getDepartmentUuid().isBlank()
                    ? person.getDepartmentUuid() : UNASSIGNED;
            byDepartment.computeIfAbsent(dept, k -> new Acc(baseCurrencyCode)).merge(e.getValue());
            total.merge(e.getValue());
            employeeRows.add(e.getValue().toGroup(e.getKey(), dept, nameOf(person), 1, null));
        }

        // ---- budgets of the current fiscal year, per department (cost center's department)
        String fiscalYear = ExpenseReportServiceImpl.fiscalYearFor(LocalDate.now());
        Map<String, BudgetAcc> costCenterBudgets = new HashMap<>();
        Map<String, BudgetAcc> budgets = budgets(fiscalYear, costCenterBudgets, costCenters);
        BudgetAcc totalBudget = new BudgetAcc();
        budgets.values().forEach(totalBudget::merge);

        Set<String> departmentKeys = new HashSet<>(byDepartment.keySet());
        departmentKeys.addAll(budgets.keySet());
        List<Group> departmentRows = new ArrayList<>();
        for (String dept : departmentKeys) {
            Acc a = byDepartment.getOrDefault(dept, new Acc(baseCurrencyCode));
            long employees = employeeRows.stream().filter(g -> dept.equals(g.departmentKey())).count();
            BudgetAcc b = budgets.get(dept);
            departmentRows.add(a.toGroup(dept, null, null, employees, b == null ? null : b.toBudget()));
        }
        Comparator<Group> bySpend = Comparator.comparing((Group g) -> g.expenses().claimed().add(g.advances().paidOut())).reversed();
        departmentRows.sort(bySpend);
        employeeRows.sort(bySpend);

        // ---- cost centers: every one with activity in the range or a budget this year
        Set<String> costCenterKeys = new HashSet<>(byCostCenter.keySet());
        costCenterKeys.addAll(costCenterBudgets.keySet());
        List<Group> costCenterRows = new ArrayList<>();
        for (String key : costCenterKeys) {
            CostCenter cc = costCenters.get(key);
            String dept = cc == null || cc.getDepartmentUuid() == null ? UNASSIGNED : cc.getDepartmentUuid().toString();
            String name = cc == null ? null : (cc.getCostCenterName() != null ? cc.getCostCenterName() : cc.getCostCenterCode());
            BudgetAcc b = costCenterBudgets.get(key);
            costCenterRows.add(byCostCenter.getOrDefault(key, new Acc(baseCurrencyCode))
                    .toGroup(key, dept, name, 0, b == null ? null : b.toBudget()));
        }
        costCenterRows.sort(bySpend.thenComparing(
                (Group g) -> g.budget() == null ? BigDecimal.ZERO : g.budget().budget(), Comparator.reverseOrder()));

        LocalDate today = LocalDate.now();
        List<Point> timeline = timeline(from, to == null || to.isAfter(today) ? today : to, paidOutOn, claimedOn);

        return new EmployeeMoneyReportResponse(baseCurrencyCode, fiscalYear, from, to,
                total.toGroup(TOTAL, null, null, employeeRows.size(), totalBudget.count == 0 ? null : totalBudget.toBudget()),
                departmentRows, employeeRows, costCenterRows, timeline,
                taxReview(reports.stream().filter(r -> r.getReportStatus() != ReportStatus.CANCELLED).map(ExpenseReport::getReportId).toList()));
    }

    private static boolean inRange(LocalDateTime at, LocalDateTime start, LocalDateTime end) {
        if (start == null && end == null) return true;
        if (at == null) return false;
        return (start == null || !at.isBefore(start)) && (end == null || at.isBefore(end));
    }

    private static String nameOf(EmployeeCache p) {
        if (p == null) return null;
        String name = ((p.getFirstName() == null ? "" : p.getFirstName()) + " " + (p.getLastName() == null ? "" : p.getLastName())).trim();
        return name.isEmpty() ? null : name;
    }

    private Acc costCenterAcc(Map<String, Acc> map, Map<String, CostCenter> seen, CostCenter cc) {
        String key = cc == null || cc.getCostCenterId() == null ? UNASSIGNED : cc.getCostCenterId().toString();
        if (cc != null) seen.putIfAbsent(key, cc);
        return map.computeIfAbsent(key, k -> new Acc(baseCurrencyCode));
    }

    /**
     * Advances paid out and expenses claimed per bucket from {@code from} (or the first event) to {@code to}, every bucket
     * present so the lines are continuous. Up to 31 days: per day; up to 120 days: per week (Monday start); else per month.
     */
    static List<Point> timeline(LocalDate from, LocalDate to, Map<LocalDate, BigDecimal> paidOut, Map<LocalDate, BigDecimal[]> claimed) {
        LocalDate first = from != null ? from
                : Stream.concat(paidOut.keySet().stream(), claimed.keySet().stream()).min(Comparator.naturalOrder()).orElse(to);
        if (first.isAfter(to)) first = to;
        long days = ChronoUnit.DAYS.between(first, to) + 1;
        String granularity = days <= 31 ? "DAY" : days <= 120 ? "WEEK" : "MONTH";
        DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
        DateTimeFormatter monthFmt = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

        TreeMap<LocalDate, BigDecimal[]> buckets = new TreeMap<>();
        for (LocalDate d = bucketOf(first, granularity); !d.isAfter(to); d = next(d, granularity)) {
            buckets.put(d, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        }
        final LocalDate lo = first;
        paidOut.forEach((d, v) -> {
            if (!d.isBefore(lo) && !d.isAfter(to)) { BigDecimal[] b = buckets.get(bucketOf(d, granularity)); b[0] = b[0].add(v); }
        });
        claimed.forEach((d, v) -> {
            if (!d.isBefore(lo) && !d.isAfter(to)) {
                BigDecimal[] b = buckets.get(bucketOf(d, granularity));
                b[1] = b[1].add(v[0]);
                b[2] = b[2].add(v[1]);
                b[3] = b[3].add(v[2]);
            }
        });

        List<Point> out = new ArrayList<>();
        buckets.forEach((d, v) -> out.add(new Point(d.toString(),
                "MONTH".equals(granularity) ? d.format(monthFmt) : d.format(dayFmt), granularity, v[0], v[1], v[2], v[3])));
        return out;
    }

    private static BigDecimal[] addAll(BigDecimal[] a, BigDecimal[] b) {
        BigDecimal[] out = new BigDecimal[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i].add(b[i]);
        return out;
    }

    /** Lines waiting for Finance with a failed tax check (now), and scanned / mismatched receipts among the range's lines. */
    private TaxReview taxReview(Collection<UUID> reportIds) {
        long awaiting = em.createQuery("select count(l) from ExpenseLineItem l"
                        + " where l.taxValidationStatus in :flagged and l.report.reportStatus = :pendingFinance", Long.class)
                .setParameter("flagged", EnumSet.of(TaxValidationStatus.REQUIRES_FINANCE_REVIEW, TaxValidationStatus.MISMATCH))
                .setParameter("pendingFinance", ReportStatus.PENDING_FINANCE_VERIFICATION)
                .getSingleResult();
        if (reportIds.isEmpty()) return new TaxReview(awaiting, 0, 0);
        Object[] ocr = em.createQuery("select count(l), sum(case when l.taxValidationReasons like '%OCR_MISMATCH%' then 1 else 0 end)"
                        + " from ExpenseLineItem l where l.ocrTaxAmount is not null and l.report.reportId in :ids", Object[].class)
                .setParameter("ids", Set.copyOf(reportIds)).getSingleResult();
        long scanned = ocr[0] == null ? 0 : ((Number) ocr[0]).longValue();
        long mismatched = ocr[1] == null ? 0 : ((Number) ocr[1]).longValue();
        return new TaxReview(awaiting, scanned, mismatched);
    }

    private static LocalDate bucketOf(LocalDate d, String granularity) {
        return switch (granularity) {
            case "WEEK" -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "MONTH" -> d.withDayOfMonth(1);
            default -> d;
        };
    }

    private static LocalDate next(LocalDate d, String granularity) {
        return switch (granularity) {
            case "WEEK" -> d.plusWeeks(1);
            case "MONTH" -> d.plusMonths(1);
            default -> d.plusDays(1);
        };
    }

    private Acc acc(Map<String, Acc> map, String employeeId) {
        return map.computeIfAbsent(employeeId == null ? "unknown" : employeeId, k -> new Acc(baseCurrencyCode));
    }

    /** [line total, tax, recoverable tax, Finance-verified recoverable tax] per report, base currency, in one grouped query. */
    private Map<UUID, BigDecimal[]> lineTotals(Collection<UUID> reportIds) {
        if (reportIds.isEmpty()) return Map.of();
        Map<UUID, BigDecimal[]> out = new HashMap<>();
        for (Object[] row : em.createQuery("""
                        select l.report.reportId, sum(l.baseAmount), sum(coalesce(l.baseTaxAmount, 0)),
                               sum(coalesce(l.baseRecoverableTaxAmount, 0)),
                               sum(case when l.taxValidationStatus in :verified then coalesce(l.baseRecoverableTaxAmount, 0) else 0 end)
                        from ExpenseLineItem l where l.report.reportId in :ids group by l.report.reportId
                        """, Object[].class).setParameter("ids", Set.copyOf(reportIds))
                .setParameter("verified", EnumSet.of(TaxValidationStatus.FINANCE_VERIFIED, TaxValidationStatus.FINANCE_ADJUSTED))
                .getResultList()) {
            out.put((UUID) row[0], new BigDecimal[]{nz(row[1]), nz(row[2]), nz(row[3]), nz(row[4])});
        }
        return out;
    }

    /**
     * The fiscal year's budgets per department, with what ACTIVE encumbrances reserve from each; also fills
     * {@code byCostCenter} (per cost center id) and records each budgeted cost center in {@code costCenters}.
     */
    private Map<String, BudgetAcc> budgets(String fiscalYear, Map<String, BudgetAcc> byCostCenter, Map<String, CostCenter> costCenters) {
        List<CostCenterBudget> rows = em.createQuery(
                        "select b from CostCenterBudget b join fetch b.costCenter where lower(b.fiscalYear) = lower(:fy)",
                        CostCenterBudget.class)
                .setParameter("fy", fiscalYear).getResultList();
        Map<UUID, BigDecimal> reserved = new HashMap<>();
        for (Object[] row : encumbranceRepository.sumAmountGroupedByBudget(BudgetEncumbranceStatus.ACTIVE)) {
            reserved.put((UUID) row[0], nz(row[1]));
        }
        Map<String, BudgetAcc> out = new HashMap<>();
        for (CostCenterBudget b : rows) {
            CostCenter cc = b.getCostCenter();
            UUID dept = cc == null ? null : cc.getDepartmentUuid();
            BigDecimal held = reserved.getOrDefault(b.getBudgetId(), BigDecimal.ZERO);
            out.computeIfAbsent(dept == null ? UNASSIGNED : dept.toString(), k -> new BudgetAcc()).add(b, held);
            if (cc != null && cc.getCostCenterId() != null) {
                String key = cc.getCostCenterId().toString();
                costCenters.putIfAbsent(key, cc);
                byCostCenter.computeIfAbsent(key, k -> new BudgetAcc()).add(b, held);
            }
        }
        return out;
    }

    static BigDecimal nz(Object v) {
        return v instanceof BigDecimal b ? b : BigDecimal.ZERO;
    }

    // ------------------------------------------------------------------ accumulators

    /** Running totals for one employee, department or the organization. */
    static final class Acc {
        final String baseCurrency;
        long advanceCount, reportCount;
        BigDecimal requested = BigDecimal.ZERO, pendingApproval = BigDecimal.ZERO, approved = BigDecimal.ZERO,
                approvedNotPaid = BigDecimal.ZERO, paidOut = BigDecimal.ZERO, carriedIn = BigDecimal.ZERO,
                used = BigDecimal.ZERO, salary = BigDecimal.ZERO, refunded = BigDecimal.ZERO,
                carriedOut = BigDecimal.ZERO, outstanding = BigDecimal.ZERO;
        BigDecimal claimed = BigDecimal.ZERO, inApproval = BigDecimal.ZERO, sentBack = BigDecimal.ZERO,
                rejected = BigDecimal.ZERO, approvedClaims = BigDecimal.ZERO, covered = BigDecimal.ZERO,
                noPayment = BigDecimal.ZERO, paid = BigDecimal.ZERO, due = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO, recoverable = BigDecimal.ZERO, verifiedRecoverable = BigDecimal.ZERO;

        Acc(String baseCurrency) {
            this.baseCurrency = baseCurrency;
        }

        void addReport(ExpenseReport r, BigDecimal gross, BigDecimal lineTax, BigDecimal lineRecoverable) {
            addReport(r, gross, lineTax, lineRecoverable, BigDecimal.ZERO);
        }

        void addReport(ExpenseReport r, BigDecimal gross, BigDecimal lineTax, BigDecimal lineRecoverable, BigDecimal lineVerifiedRecoverable) {
            ReportStatus s = r.getReportStatus();
            if (s == ReportStatus.CANCELLED) return;
            reportCount++;
            claimed = claimed.add(gross);
            tax = tax.add(lineTax);
            recoverable = recoverable.add(lineRecoverable);
            verifiedRecoverable = verifiedRecoverable.add(lineVerifiedRecoverable);
            switch (s) {
                case AWAITING_CORRECTION, QUERY_RAISED, POLICY_REJECTED -> sentBack = sentBack.add(gross);
                case REJECTED -> rejected = rejected.add(gross);
                case APPROVED, REIMBURSED, CLOSED -> {
                    approvedClaims = approvedClaims.add(gross);
                    BigDecimal owed = r.getReimbursableAmount() == null ? gross
                            : r.getReimbursableAmount().min(gross).max(BigDecimal.ZERO);
                    BigDecimal notPayable = gross.subtract(owed);
                    if (r.getCashAdvance() != null) covered = covered.add(notPayable); else noPayment = noPayment.add(notPayable);
                    boolean completed = s == ReportStatus.REIMBURSED || r.getPaymentRoutingStatus() == PaymentRoutingStatus.PAYMENT_COMPLETED;
                    BigDecimal paidPart = completed ? owed
                            : (r.getPaidAmount() == null ? BigDecimal.ZERO : r.getPaidAmount().min(owed).max(BigDecimal.ZERO));
                    paid = paid.add(paidPart);
                    due = due.add(owed.subtract(paidPart));
                }
                // PENDING_APPROVAL, SUBMITTED, PENDING_FINANCE_VERIFICATION and anything added later: still in approval.
                default -> inApproval = inApproval.add(gross);
            }
        }

        void addAdvance(CashAdvance a, List<CashAdvanceAdjustment> adjustments, List<CashAdvanceRepayment> repayments, BigDecimal owed) {
            String status = a.getStatus() == null ? "" : a.getStatus().toUpperCase();
            if (NOT_LIVE_ADVANCE.contains(status)) return;
            advanceCount++;
            // Amounts are in the advance's currency; convert with its own base rate (baseAmount / amount).
            BigDecimal rate = rate(a);
            BigDecimal req = base(a.getRequestedAmount() != null ? a.getRequestedAmount() : a.getAmount(), rate);
            requested = requested.add(req);
            if (a.getDisbursedAt() == null) {
                if (PENDING_ADVANCE.contains(status)) pendingApproval = pendingApproval.add(req);
                else {
                    BigDecimal appr = base(a.getApprovedAmount() != null ? a.getApprovedAmount() : a.getAmount(), rate);
                    approved = approved.add(appr);
                    approvedNotPaid = approvedNotPaid.add(appr);
                }
                return;
            }
            approved = approved.add(base(a.getApprovedAmount() != null ? a.getApprovedAmount() : a.getAmount(), rate));
            paidOut = paidOut.add(base(a.getDisbursedAmount() != null ? a.getDisbursedAmount() : a.getAmount(), rate));
            carriedIn = carriedIn.add(base(CashAdvanceBalanceRules.carriedIn(a), rate));
            carriedOut = carriedOut.add(base(CashAdvanceBalanceRules.carriedOut(a), rate));
            used = used.add(base(CashAdvanceBalanceCalculator.activeOffsets(adjustments), rate));
            for (CashAdvanceRepayment p : repayments) {
                boolean payroll = "PAYROLL".equalsIgnoreCase(p.getPaymentMethod()) || "PAYROLL".equalsIgnoreCase(p.getPaymentMode());
                BigDecimal amt = base(p.getAmount(), rate);
                if (payroll) salary = salary.add(amt); else refunded = refunded.add(amt);
            }
            outstanding = outstanding.add(base(owed == null ? BigDecimal.ZERO : owed.max(BigDecimal.ZERO), rate));
        }

        /** What was paid out on the advance, in the base currency. */
        BigDecimal paidOutBase(CashAdvance a) {
            return base(a.getDisbursedAmount() != null ? a.getDisbursedAmount() : a.getAmount(), rate(a));
        }

        /**
         * Rate from the advance's currency to the base currency. An advance already in the base currency is not converted.
         * Otherwise the rate frozen at request time: baseAmount was set from the requested amount, before any carry-in
         * grew {@code amount}, so it is divided by the requested amount, not the current amount.
         */
        private BigDecimal rate(CashAdvance a) {
            String code = a.getCurrency() == null ? null : a.getCurrency().getCurrencyCode();
            if (code == null || code.equalsIgnoreCase(baseCurrency)) return BigDecimal.ONE;
            BigDecimal requestedAmt = a.getRequestedAmount() != null ? a.getRequestedAmount() : a.getAmount();
            if (a.getBaseAmount() != null && requestedAmt != null && requestedAmt.signum() > 0) {
                return a.getBaseAmount().divide(requestedAmt, 8, RoundingMode.HALF_UP);
            }
            return BigDecimal.ONE;
        }

        private static BigDecimal base(BigDecimal v, BigDecimal rate) {
            return v == null ? BigDecimal.ZERO : v.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        }

        void merge(Acc o) {
            advanceCount += o.advanceCount; reportCount += o.reportCount;
            requested = requested.add(o.requested); pendingApproval = pendingApproval.add(o.pendingApproval);
            approved = approved.add(o.approved); approvedNotPaid = approvedNotPaid.add(o.approvedNotPaid);
            paidOut = paidOut.add(o.paidOut); carriedIn = carriedIn.add(o.carriedIn); used = used.add(o.used);
            salary = salary.add(o.salary); refunded = refunded.add(o.refunded); carriedOut = carriedOut.add(o.carriedOut);
            outstanding = outstanding.add(o.outstanding);
            claimed = claimed.add(o.claimed); inApproval = inApproval.add(o.inApproval); sentBack = sentBack.add(o.sentBack);
            rejected = rejected.add(o.rejected); approvedClaims = approvedClaims.add(o.approvedClaims);
            covered = covered.add(o.covered); noPayment = noPayment.add(o.noPayment); paid = paid.add(o.paid); due = due.add(o.due);
            tax = tax.add(o.tax); recoverable = recoverable.add(o.recoverable); verifiedRecoverable = verifiedRecoverable.add(o.verifiedRecoverable);
        }

        Group toGroup(String key, String departmentKey, String name, long employeeCount, Budget budget) {
            return new Group(key, departmentKey, name, employeeCount,
                    new Advances(advanceCount, requested, pendingApproval, approved, approvedNotPaid, paidOut, carriedIn,
                            used, salary, refunded, carriedOut, outstanding),
                    new Expenses(reportCount, claimed, inApproval, sentBack, rejected, approvedClaims, covered, noPayment, paid, due),
                    new Tax(tax, recoverable, tax.subtract(recoverable).max(BigDecimal.ZERO), verifiedRecoverable),
                    budget);
        }
    }

    /** Budgets of one department (or all). */
    static final class BudgetAcc {
        int count;
        BigDecimal budget = BigDecimal.ZERO, available = BigDecimal.ZERO, reserved = BigDecimal.ZERO;

        void add(CostCenterBudget b, BigDecimal reservedAmount) {
            count++;
            budget = budget.add(nz(b.getBudgetAmount()).add(nz(b.getRolloverFromPrevious())));
            available = available.add(nz(b.getAvailableBudget()));
            reserved = reserved.add(reservedAmount);
        }

        void merge(BudgetAcc o) {
            count += o.count; budget = budget.add(o.budget); available = available.add(o.available); reserved = reserved.add(o.reserved);
        }

        Budget toBudget() {
            BigDecimal effective = available.subtract(reserved);
            return new Budget(count, budget, available, reserved, effective, budget.subtract(effective));
        }
    }
}
