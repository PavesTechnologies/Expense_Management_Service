package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.DashboardResponse;
import com.expense_management_service.dto.response.DashboardResponse.Budget;
import com.expense_management_service.dto.response.DashboardResponse.Item;
import com.expense_management_service.dto.response.DashboardResponse.Kpi;
import com.expense_management_service.dto.response.DashboardResponse.Slice;
import com.expense_management_service.dto.response.DashboardResponse.Stage;
import com.expense_management_service.dto.response.DashboardResponse.TrendPoint;
import com.expense_management_service.entity.ApprovalAssignment;
import com.expense_management_service.entity.CostCenterBudget;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.AssignmentStatus;
import com.expense_management_service.enums.FinanceVerificationStatus;
import com.expense_management_service.enums.InvoiceHandoffStatus;
import com.expense_management_service.enums.LevelInstanceStatus;
import com.expense_management_service.enums.LevelType;
import com.expense_management_service.enums.LineItemReviewStatus;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.DashboardService;
import com.expense_management_service.service.DelegationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Aggregates for the role dashboards. Amounts are always summed from line items' base-currency
 * {@code baseAmount}; report totals are in each report's own currency and can't be added up.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardServiceImpl implements DashboardService {

    private static final int TREND_MONTHS = 6;
    private static final int ADMIN_TREND_MONTHS = 12;
    private static final int LIST_SIZE = 6;

    private static final Set<ReportStatus> NEEDS_EMPLOYEE_ACTION =
            EnumSet.of(ReportStatus.AWAITING_CORRECTION, ReportStatus.POLICY_REJECTED, ReportStatus.QUERY_RAISED);
    private static final Set<ReportStatus> NOT_SUBMITTED = EnumSet.of(ReportStatus.DRAFT, ReportStatus.CANCELLED);

    @PersistenceContext
    private EntityManager em;

    private final CurrentUserService currentUserService;
    private final DelegationService delegationService;

    @Value("${exchange.rate.base-currency}")
    private String baseCurrencyCode;

    // ------------------------------------------------------------------ employee

    @Override
    public DashboardResponse employee() {
        String me = currentUserService.getEmployeeId();
        List<ExpenseReport> reports = em.createQuery(
                        "select r from ExpenseReport r where r.employeeId = :me", ExpenseReport.class)
                .setParameter("me", me).getResultList();
        Map<UUID, BigDecimal> amounts = baseAmountsByReport(reports.stream().map(ExpenseReport::getReportId).toList());

        long drafts = count(reports, r -> r.getReportStatus() == ReportStatus.DRAFT);
        long needsAction = count(reports, r -> NEEDS_EMPLOYEE_ACTION.contains(r.getReportStatus()));
        long inApproval = count(reports, r -> r.getReportStatus() == ReportStatus.PENDING_APPROVAL);
        long atFinance = count(reports, r -> r.getReportStatus() == ReportStatus.PENDING_FINANCE_VERIFICATION);
        long withAp = count(reports, r -> r.getPaymentRoutingStatus() == PaymentRoutingStatus.APPROVED_FOR_PAYMENT);
        long paid = count(reports, r -> r.getPaymentRoutingStatus() == PaymentRoutingStatus.PAYMENT_COMPLETED);

        BigDecimal awaitingReimbursement = sum(reports, amounts, r -> r.getPaymentRoutingStatus() == PaymentRoutingStatus.APPROVED_FOR_PAYMENT);
        BigDecimal reimbursed = sum(reports, amounts, r -> r.getPaymentRoutingStatus() == PaymentRoutingStatus.PAYMENT_COMPLETED);

        LocalDate trendStart = firstMonth(TREND_MONTHS).atDay(1);
        List<Object[]> lines = em.createQuery("""
                        select l.expenseDate, l.baseAmount, c.categoryName from ExpenseLineItem l left join l.category c
                        where l.report.employeeId = :me and l.expenseDate >= :start
                          and l.report.reportStatus not in :notSubmitted
                        """, Object[].class)
                .setParameter("me", me).setParameter("start", trendStart).setParameter("notSubmitted", NOT_SUBMITTED)
                .getResultList();
        BigDecimal claimed = lines.stream().map(row -> amount(row[1])).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Kpi> kpis = List.of(
                new Kpi("claimed", "Claimed (6 months)", claimed, "money", "Submitted expenses", "indigo"),
                new Kpi("inProgress", "In progress", BigDecimal.valueOf(inApproval + atFinance), "count", "With approvers or Finance", "blue"),
                new Kpi("needsAction", "Needs your action", BigDecimal.valueOf(needsAction), "count", "Sent back or queried", needsAction > 0 ? "amber" : "gray"),
                new Kpi("awaiting", "Awaiting reimbursement", awaitingReimbursement, "money", withAp + " report(s) with AP", "indigo"),
                new Kpi("reimbursed", "Reimbursed", reimbursed, "money", paid + " report(s) paid", "emerald"));

        List<Stage> pipeline = List.of(
                new Stage("draft", "Draft", drafts),
                new Stage("approval", "Manager approval", inApproval),
                new Stage("finance", "Finance check", atFinance),
                new Stage("ap", "With AP", withAp),
                new Stage("paid", "Paid", paid));

        List<Item> attention = reports.stream()
                .filter(r -> NEEDS_EMPLOYEE_ACTION.contains(r.getReportStatus()) || r.getReportStatus() == ReportStatus.DRAFT)
                .sorted(Comparator.comparing((ExpenseReport r) -> NEEDS_EMPLOYEE_ACTION.contains(r.getReportStatus()) ? 0 : 1)
                        .thenComparing(ExpenseReport::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(LIST_SIZE)
                .map(r -> item(r, amounts, r.getUpdatedAt(),
                        r.getReportStatus() == ReportStatus.DRAFT ? "Not submitted yet" : "Fix and resubmit"))
                .toList();

        List<Item> activity = reports.stream()
                .sorted(Comparator.comparing(ExpenseReport::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(LIST_SIZE)
                .map(r -> item(r, amounts, r.getUpdatedAt(), null))
                .toList();

        return new DashboardResponse("employee", baseCurrencyCode, kpis,
                "Your monthly spend", monthlyAmounts(lines, TREND_MONTHS),
                pipeline,
                "Your reports by status", statusSlices(reports, amounts),
                "Spend by category (6 months)", categoryRanking(lines, 5),
                List.of(), List.of(), attention, activity);
    }

    // ------------------------------------------------------------------ manager

    @Override
    public DashboardResponse manager() {
        String me = currentUserService.getEmployeeId();
        LocalDateTime now = LocalDateTime.now();

        // Same scope as the approvals page's my-queue: the caller's own assignments plus any
        // delegated to them by another approver.
        Set<String> actingFor = delegationService.resolveApproverIdsActingFor(me);
        List<ApprovalAssignment> pending = em.createQuery("""
                        select a from ApprovalAssignment a join fetch a.levelInstance li join fetch li.report r
                        where a.approverId in :ids and a.status = :active
                        """, ApprovalAssignment.class)
                .setParameter("ids", actingFor).setParameter("active", AssignmentStatus.ACTIVE).getResultList();
        List<ExpenseReport> pendingReports = pending.stream().map(a -> a.getLevelInstance().getReport()).distinct().toList();
        Map<UUID, BigDecimal> amounts = baseAmountsByReport(pendingReports.stream().map(ExpenseReport::getReportId).toList());
        BigDecimal pendingValue = pendingReports.stream().map(r -> amounts.getOrDefault(r.getReportId(), BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long overdue = pending.stream().filter(a -> a.getDueDate() != null && a.getDueDate().isBefore(now)).count();

        LocalDateTime trendStart = firstMonth(TREND_MONTHS).atDay(1).atStartOfDay();
        // ApprovalLineItemReview.actedBy is only set when a delegate acted; null means the level's
        // assigned approver acted. So a decision is the caller's if they acted as a delegate, or
        // if nobody else did and the caller held the (now completed) assignment on that level.
        List<Object[]> decisions = em.createQuery("""
                        select rv.status, rv.actionedAt from ApprovalLineItemReview rv
                        where rv.actionedAt >= :start and rv.status <> :pendingStatus
                          and (rv.actedBy = :me
                               or (rv.actedBy is null and exists (
                                   select 1 from ApprovalAssignment a
                                   where a.levelInstance = rv.levelInstance and a.approverId = :me
                                     and a.status = :completed)))
                        """, Object[].class)
                .setParameter("completed", AssignmentStatus.COMPLETED)
                .setParameter("me", me).setParameter("start", trendStart)
                .setParameter("pendingStatus", LineItemReviewStatus.PENDING).getResultList();

        LocalDateTime last30 = now.minusDays(30);
        long approved30 = decisions.stream().filter(d -> d[0] == LineItemReviewStatus.APPROVED && ((LocalDateTime) d[1]).isAfter(last30)).count();
        long sentBack30 = decisions.stream().filter(d -> d[0] == LineItemReviewStatus.NEEDS_CORRECTION && ((LocalDateTime) d[1]).isAfter(last30)).count();
        long rejected30 = em.createQuery(
                        "select count(r) from ExpenseReport r where r.rejectedBy = :me and r.rejectedAt >= :since", Long.class)
                .setParameter("me", me).setParameter("since", last30).getSingleResult();

        List<Kpi> kpis = List.of(
                new Kpi("pending", "Waiting for you", BigDecimal.valueOf(pendingReports.size()), "count", "Reports to review", "indigo"),
                new Kpi("pendingValue", "Value waiting", pendingValue, "money", "Across those reports", "blue"),
                new Kpi("overdue", "Overdue", BigDecimal.valueOf(overdue), "count", "Past their due date", overdue > 0 ? "rose" : "gray"),
                new Kpi("approved30", "Approved (30 days)", BigDecimal.valueOf(approved30), "count", "Line items", "emerald"),
                new Kpi("sentBack30", "Sent back (30 days)", BigDecimal.valueOf(sentBack30), "count", "Needs correction", "amber"));

        // Trend: approved line items (count) with sent-back as the secondary series.
        Map<YearMonth, long[]> byMonth = emptyMonths(TREND_MONTHS, () -> new long[2]);
        decisions.forEach(d -> {
            long[] slot = byMonth.get(YearMonth.from((LocalDateTime) d[1]));
            if (slot != null) slot[d[0] == LineItemReviewStatus.APPROVED ? 0 : 1]++;
        });
        List<TrendPoint> trend = byMonth.entrySet().stream()
                .map(e -> new TrendPoint(e.getKey().toString(), monthLabel(e.getKey()), BigDecimal.valueOf(e.getValue()[0]), e.getValue()[0], e.getValue()[1]))
                .toList();

        List<Slice> decisionMix = List.of(
                new Slice("approved", "Approved", approved30, null),
                new Slice("sentBack", "Sent back", sentBack30, null),
                new Slice("rejected", "Rejected", rejected30, null));

        List<Slice> aging = agingBuckets(pending.stream().map(ApprovalAssignment::getAssignedAt).toList(), now);

        List<Item> attention = pending.stream()
                .sorted(Comparator.comparing(ApprovalAssignment::getAssignedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(LIST_SIZE)
                .map(a -> {
                    ExpenseReport r = a.getLevelInstance().getReport();
                    String note = a.getDueDate() != null && a.getDueDate().isBefore(now) ? "Overdue" : "Waiting " + daysSince(a.getAssignedAt(), now) + "d";
                    return item(r, amounts, a.getAssignedAt(), note);
                })
                .toList();

        return new DashboardResponse("manager", baseCurrencyCode, kpis,
                "Your decisions per month", trend,
                List.of(),
                "Decisions (last 30 days)", decisionMix,
                null, List.of(),
                aging, List.of(), attention, List.of());
    }

    // ------------------------------------------------------------------ finance

    @Override
    public DashboardResponse finance() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime monthStart = YearMonth.now().atDay(1).atStartOfDay();

        List<Object[]> queue = em.createQuery("""
                        select li.report, li.createdAt from ApprovalLevelInstance li
                        where li.status = :active and li.levelType = :finance
                          and li.report.reportStatus = :pendingFinance
                        """, Object[].class)
                .setParameter("active", LevelInstanceStatus.ACTIVE).setParameter("finance", LevelType.FINANCE_VERIFICATION)
                .setParameter("pendingFinance", ReportStatus.PENDING_FINANCE_VERIFICATION).getResultList();
        List<ExpenseReport> queueReports = queue.stream().map(row -> (ExpenseReport) row[0]).distinct().toList();
        Map<UUID, BigDecimal> amounts = baseAmountsByReport(queueReports.stream().map(ExpenseReport::getReportId).toList());
        BigDecimal queueValue = queueReports.stream().map(r -> amounts.getOrDefault(r.getReportId(), BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDateTime trendStart = firstMonth(TREND_MONTHS).atDay(1).atStartOfDay();
        List<Object[]> reviews = em.createQuery("""
                        select rv.status, rv.actionedAt, l.baseAmount, rv.levelInstance.report.reportId from FinanceVerificationReview rv
                        join rv.lineItem l
                        where rv.actionedAt >= :start and rv.status <> :pendingStatus
                        """, Object[].class)
                .setParameter("start", trendStart).setParameter("pendingStatus", FinanceVerificationStatus.PENDING).getResultList();

        long verifiedThisMonth = reviews.stream()
                .filter(r -> r[0] == FinanceVerificationStatus.VERIFIED && !((LocalDateTime) r[1]).isBefore(monthStart))
                .map(r -> r[3]).distinct().count();
        long openQueries = em.createQuery("""
                        select count(distinct rv.levelInstance.report) from FinanceVerificationReview rv
                        where rv.status = :queried and rv.levelInstance.report.reportStatus = :awaiting
                        """, Long.class)
                .setParameter("queried", FinanceVerificationStatus.QUERIED)
                .setParameter("awaiting", ReportStatus.AWAITING_CORRECTION).getSingleResult();
        long exceptions = em.createQuery(
                        "select count(v) from PolicyViolation v where v.lineItem.report.reportStatus = :pendingFinance", Long.class)
                .setParameter("pendingFinance", ReportStatus.PENDING_FINANCE_VERIFICATION).getSingleResult();
        long withAp = countByRouting(PaymentRoutingStatus.APPROVED_FOR_PAYMENT);
        long invoicePending = em.createQuery(
                        "select count(r) from ExpenseReport r where r.invoiceHandoffStatus = :pending", Long.class)
                .setParameter("pending", InvoiceHandoffStatus.PENDING).getSingleResult();

        List<Kpi> kpis = List.of(
                new Kpi("toVerify", "To verify", BigDecimal.valueOf(queueReports.size()), "count", "Reports in the queue", "amber"),
                new Kpi("queueValue", "Queue value", queueValue, "money", "Awaiting verification", "indigo"),
                new Kpi("queries", "Open queries", BigDecimal.valueOf(openQueries), "count", "Back with employees", openQueries > 0 ? "rose" : "gray"),
                new Kpi("verified", "Verified this month", BigDecimal.valueOf(verifiedThisMonth), "count", "Reports", "emerald"),
                new Kpi("exceptions", "Policy exceptions", BigDecimal.valueOf(exceptions), "count", "On reports in the queue", exceptions > 0 ? "amber" : "gray"));

        // Trend: verified amount per month, with verified / queried line counts.
        Map<YearMonth, Object[]> byMonth = emptyMonths(TREND_MONTHS, () -> new Object[]{BigDecimal.ZERO, 0L, 0L});
        reviews.forEach(r -> {
            Object[] slot = byMonth.get(YearMonth.from((LocalDateTime) r[1]));
            if (slot == null) return;
            if (r[0] == FinanceVerificationStatus.VERIFIED) {
                slot[0] = ((BigDecimal) slot[0]).add(amount(r[2]));
                slot[1] = (long) slot[1] + 1;
            } else {
                slot[2] = (long) slot[2] + 1;
            }
        });
        List<TrendPoint> trend = byMonth.entrySet().stream()
                .map(e -> new TrendPoint(e.getKey().toString(), monthLabel(e.getKey()), (BigDecimal) e.getValue()[0], (long) e.getValue()[1], (long) e.getValue()[2]))
                .toList();

        List<Stage> pipeline = List.of(
                new Stage("toVerify", "To verify", queueReports.size()),
                new Stage("queried", "Queried", openQueries),
                new Stage("withAp", "With AP", withAp),
                new Stage("paid", "Paid", countByRouting(PaymentRoutingStatus.PAYMENT_COMPLETED)),
                new Stage("invoice", "Invoice pending", invoicePending));

        List<Object[]> queueByCategory = em.createQuery("""
                        select c.categoryName, count(l), sum(l.baseAmount) from ExpenseLineItem l left join l.category c
                        where l.report.reportStatus = :pendingFinance group by c.categoryName
                        """, Object[].class)
                .setParameter("pendingFinance", ReportStatus.PENDING_FINANCE_VERIFICATION).getResultList();

        List<Item> attention = queue.stream()
                .sorted(Comparator.comparing(row -> (LocalDateTime) row[1], Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(LIST_SIZE)
                .map(row -> item((ExpenseReport) row[0], amounts, (LocalDateTime) row[1], "Waiting " + daysSince((LocalDateTime) row[1], now) + "d"))
                .toList();

        List<Item> activity = latestFinanceActions();

        return new DashboardResponse("finance", baseCurrencyCode, kpis,
                "Verified per month", trend,
                pipeline,
                null, List.of(),
                "Queue by category", rankGrouped(queueByCategory, 5),
                agingBuckets(queue.stream().map(row -> (LocalDateTime) row[1]).toList(), now),
                List.of(), attention, activity);
    }

    private List<Item> latestFinanceActions() {
        List<Object[]> rows = em.createQuery("""
                        select rv.levelInstance.report, rv.status, rv.actionedAt, rv.actedBy from FinanceVerificationReview rv
                        where rv.actionedAt is not null and rv.status <> :pendingStatus order by rv.actionedAt desc
                        """, Object[].class)
                .setParameter("pendingStatus", FinanceVerificationStatus.PENDING)
                .setMaxResults(40).getResultList();
        // One row per report (its latest action), newest first.
        Map<UUID, Object[]> latest = new LinkedHashMap<>();
        rows.forEach(row -> latest.putIfAbsent(((ExpenseReport) row[0]).getReportId(), row));
        List<Object[]> top = latest.values().stream().limit(LIST_SIZE).toList();
        Map<UUID, BigDecimal> amounts = baseAmountsByReport(top.stream().map(row -> ((ExpenseReport) row[0]).getReportId()).toList());
        return top.stream().map(row -> {
            ExpenseReport r = (ExpenseReport) row[0];
            String action = row[1] == FinanceVerificationStatus.VERIFIED ? "Verified" : "Queried";
            return new Item(r.getReportId(), r.getReportNumber(), r.getTitle(), r.getEmployeeId(), action,
                    amounts.getOrDefault(r.getReportId(), BigDecimal.ZERO), (LocalDateTime) row[2], (String) row[3]);
        }).toList();
    }

    // ------------------------------------------------------------------ AP

    @Override
    public DashboardResponse ap() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime monthStart = YearMonth.now().atDay(1).atStartOfDay();

        List<ExpenseReport> toPay = em.createQuery("""
                        select r from ExpenseReport r left join fetch r.costCenter
                        where r.reportStatus = :approved and r.paymentRoutingStatus = :forPayment
                        """, ExpenseReport.class)
                .setParameter("approved", ReportStatus.APPROVED)
                .setParameter("forPayment", PaymentRoutingStatus.APPROVED_FOR_PAYMENT).getResultList();

        LocalDateTime trendStart = firstMonth(TREND_MONTHS).atDay(1).atStartOfDay();
        List<ExpenseReport> paid = em.createQuery("""
                        select r from ExpenseReport r
                        where r.paymentRoutingStatus = :completed and r.paymentCompletedAt >= :start
                        """, ExpenseReport.class)
                .setParameter("completed", PaymentRoutingStatus.PAYMENT_COMPLETED).setParameter("start", trendStart).getResultList();

        List<UUID> ids = new ArrayList<>();
        toPay.forEach(r -> ids.add(r.getReportId()));
        paid.forEach(r -> ids.add(r.getReportId()));
        Map<UUID, BigDecimal> amounts = baseAmountsByReport(ids);

        BigDecimal toPayValue = sum(toPay, amounts, r -> true);
        List<ExpenseReport> paidThisMonth = paid.stream().filter(r -> !r.getPaymentCompletedAt().isBefore(monthStart)).toList();
        BigDecimal paidThisMonthValue = sum(paidThisMonth, amounts, r -> true);
        List<ExpenseReport> paid90 = paid.stream()
                .filter(r -> r.getApprovedAt() != null && r.getPaymentCompletedAt().isAfter(now.minusDays(90))).toList();
        BigDecimal avgDaysToPay = paid90.isEmpty() ? BigDecimal.ZERO : BigDecimal.valueOf(
                paid90.stream().mapToLong(r -> Duration.between(r.getApprovedAt(), r.getPaymentCompletedAt()).toHours()).average().orElse(0) / 24.0)
                .setScale(1, RoundingMode.HALF_UP);
        long failed = countByRouting(PaymentRoutingStatus.HANDOFF_FAILED);

        List<Kpi> kpis = List.of(
                new Kpi("toPay", "To pay", BigDecimal.valueOf(toPay.size()), "count", "Reports waiting", "indigo"),
                new Kpi("toPayValue", "Amount to pay", toPayValue, "money", "Owed to employees", "blue"),
                new Kpi("paidMonth", "Paid this month", paidThisMonthValue, "money", paidThisMonth.size() + " report(s)", "emerald"),
                new Kpi("avgDays", "Avg time to pay", avgDaysToPay, "days", "Approval to payment, 90 days", "gray"),
                new Kpi("failed", "Handoff failed", BigDecimal.valueOf(failed), "count", "Needs follow-up", failed > 0 ? "rose" : "gray"));

        Map<YearMonth, Object[]> byMonth = emptyMonths(TREND_MONTHS, () -> new Object[]{BigDecimal.ZERO, 0L});
        paid.forEach(r -> {
            Object[] slot = byMonth.get(YearMonth.from(r.getPaymentCompletedAt()));
            if (slot == null) return;
            slot[0] = ((BigDecimal) slot[0]).add(amounts.getOrDefault(r.getReportId(), BigDecimal.ZERO));
            slot[1] = (long) slot[1] + 1;
        });
        List<TrendPoint> trend = byMonth.entrySet().stream()
                .map(e -> new TrendPoint(e.getKey().toString(), monthLabel(e.getKey()), (BigDecimal) e.getValue()[0], (long) e.getValue()[1], 0))
                .toList();

        Map<String, Slice> byCostCenter = new HashMap<>();
        toPay.forEach(r -> {
            String name = r.getCostCenter() != null ? r.getCostCenter().getCostCenterName() : "No cost center";
            Slice s = byCostCenter.getOrDefault(name, new Slice(name, name, 0, BigDecimal.ZERO));
            byCostCenter.put(name, new Slice(name, name, s.count() + 1, s.amount().add(amounts.getOrDefault(r.getReportId(), BigDecimal.ZERO))));
        });

        List<Item> attention = toPay.stream()
                .sorted(Comparator.comparing(ExpenseReport::getApprovedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(LIST_SIZE)
                .map(r -> item(r, amounts, r.getApprovedAt(), "Approved " + daysSince(r.getApprovedAt(), now) + "d ago"))
                .toList();
        List<Item> activity = paid.stream()
                .sorted(Comparator.comparing(ExpenseReport::getPaymentCompletedAt).reversed())
                .limit(LIST_SIZE)
                .map(r -> item(r, amounts, r.getPaymentCompletedAt(), "Paid"))
                .toList();

        return new DashboardResponse("ap", baseCurrencyCode, kpis,
                "Paid per month", trend,
                List.of(),
                null, List.of(),
                "To pay by cost center", topSlices(byCostCenter.values(), 5),
                agingBuckets(toPay.stream().map(ExpenseReport::getApprovedAt).toList(), now),
                List.of(), attention, activity);
    }

    // ------------------------------------------------------------------ admin

    @Override
    public DashboardResponse admin() {
        LocalDateTime now = LocalDateTime.now();
        YearMonth thisMonth = YearMonth.now();
        LocalDate trendStart = firstMonth(ADMIN_TREND_MONTHS).atDay(1);

        List<Object[]> lines = em.createQuery("""
                        select l.expenseDate, l.baseAmount, c.categoryName from ExpenseLineItem l left join l.category c
                        where l.expenseDate >= :start and l.report.reportStatus not in :notSubmitted
                        """, Object[].class)
                .setParameter("start", trendStart).setParameter("notSubmitted", NOT_SUBMITTED).getResultList();
        BigDecimal spendThisMonth = lines.stream()
                .filter(row -> YearMonth.from((LocalDate) row[0]).equals(thisMonth))
                .map(row -> amount(row[1])).reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDateTime monthStart = thisMonth.atDay(1).atStartOfDay();
        long submittedThisMonth = em.createQuery(
                        "select count(r) from ExpenseReport r where r.submittedAt >= :since", Long.class)
                .setParameter("since", monthStart).getSingleResult();
        long claimants = em.createQuery(
                        "select count(distinct r.employeeId) from ExpenseReport r where r.submittedAt >= :since", Long.class)
                .setParameter("since", monthStart).getSingleResult();
        List<Object[]> cycles = em.createQuery(
                        "select r.submittedAt, r.approvedAt from ExpenseReport r where r.approvedAt >= :since and r.submittedAt is not null", Object[].class)
                .setParameter("since", now.minusDays(90)).getResultList();
        BigDecimal avgApprovalDays = cycles.isEmpty() ? BigDecimal.ZERO : BigDecimal.valueOf(
                cycles.stream().mapToLong(c -> Duration.between((LocalDateTime) c[0], (LocalDateTime) c[1]).toHours()).average().orElse(0) / 24.0)
                .setScale(1, RoundingMode.HALF_UP);
        long openViolations = em.createQuery("""
                        select count(v) from PolicyViolation v
                        where v.justification is null and v.lineItem.report.reportStatus not in :closed
                        """, Long.class)
                .setParameter("closed", EnumSet.of(ReportStatus.CANCELLED, ReportStatus.REJECTED, ReportStatus.APPROVED,
                        ReportStatus.CLOSED, ReportStatus.REIMBURSED)).getSingleResult();

        List<Kpi> kpis = List.of(
                new Kpi("spendMonth", "Spend this month", spendThisMonth, "money", "Submitted expenses", "indigo"),
                new Kpi("submitted", "Reports submitted", BigDecimal.valueOf(submittedThisMonth), "count", "This month", "blue"),
                new Kpi("claimants", "Active claimants", BigDecimal.valueOf(claimants), "count", "Employees this month", "gray"),
                new Kpi("cycle", "Avg approval time", avgApprovalDays, "days", "Submit to approved, 90 days", "gray"),
                new Kpi("violations", "Open policy issues", BigDecimal.valueOf(openViolations), "count", "Unjustified, on open reports", openViolations > 0 ? "amber" : "gray"));

        List<Object[]> statusCounts = em.createQuery(
                "select r.reportStatus, count(r) from ExpenseReport r group by r.reportStatus", Object[].class).getResultList();
        Map<ReportStatus, Long> byStatus = new HashMap<>();
        statusCounts.forEach(row -> byStatus.put((ReportStatus) row[0], (Long) row[1]));

        List<Stage> pipeline = List.of(
                new Stage("draft", "Draft", byStatus.getOrDefault(ReportStatus.DRAFT, 0L)),
                new Stage("approval", "Manager approval", byStatus.getOrDefault(ReportStatus.PENDING_APPROVAL, 0L)),
                new Stage("finance", "Finance check", byStatus.getOrDefault(ReportStatus.PENDING_FINANCE_VERIFICATION, 0L)),
                new Stage("ap", "With AP", countByRouting(PaymentRoutingStatus.APPROVED_FOR_PAYMENT)),
                new Stage("paid", "Paid", countByRouting(PaymentRoutingStatus.PAYMENT_COMPLETED)));

        List<Slice> statusBreakdown = byStatus.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<ReportStatus, Long>comparingByValue().reversed())
                .map(e -> new Slice(e.getKey().name(), statusLabel(e.getKey()), e.getValue(), null))
                .toList();

        return new DashboardResponse("admin", baseCurrencyCode, kpis,
                "Monthly spend (12 months)", monthlyAmounts(lines, ADMIN_TREND_MONTHS),
                pipeline,
                "Reports by status", statusBreakdown,
                "Top categories (12 months)", categoryRanking(lines, 6),
                List.of(), budgets(), List.of(), recentSubmissions());
    }

    private List<Budget> budgets() {
        List<CostCenterBudget> all = em.createQuery(
                "select b from CostCenterBudget b join fetch b.costCenter", CostCenterBudget.class).getResultList();
        String latestYear = all.stream().map(CostCenterBudget::getFiscalYear).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        return all.stream()
                .filter(b -> Objects.equals(b.getFiscalYear(), latestYear) && b.getBudgetAmount() != null
                        && b.getBudgetAmount().signum() > 0)
                .map(b -> new Budget(b.getCostCenter().getCostCenterName(), b.getFiscalYear(), b.getBudgetAmount(),
                        b.getBudgetAmount().subtract(b.getAvailableBudget() != null ? b.getAvailableBudget() : b.getBudgetAmount()),
                        b.getWarningThreshold()))
                .sorted(Comparator.comparing((Budget b) -> b.usedAmount().divide(b.budgetAmount(), 4, RoundingMode.HALF_UP)).reversed())
                .limit(6)
                .toList();
    }

    private List<Item> recentSubmissions() {
        List<ExpenseReport> recent = em.createQuery(
                        "select r from ExpenseReport r where r.submittedAt is not null order by r.submittedAt desc", ExpenseReport.class)
                .setMaxResults(LIST_SIZE).getResultList();
        Map<UUID, BigDecimal> amounts = baseAmountsByReport(recent.stream().map(ExpenseReport::getReportId).toList());
        return recent.stream().map(r -> item(r, amounts, r.getSubmittedAt(), null)).toList();
    }

    // ------------------------------------------------------------------ helpers

    private Map<UUID, BigDecimal> baseAmountsByReport(Collection<UUID> reportIds) {
        if (reportIds.isEmpty()) return Map.of();
        List<Object[]> rows = em.createQuery(
                        "select l.report.reportId, sum(l.baseAmount) from ExpenseLineItem l where l.report.reportId in :ids group by l.report.reportId",
                        Object[].class)
                .setParameter("ids", Set.copyOf(reportIds)).getResultList();
        return rows.stream().collect(Collectors.toMap(row -> (UUID) row[0], row -> amount(row[1])));
    }

    private long countByRouting(PaymentRoutingStatus status) {
        return em.createQuery("select count(r) from ExpenseReport r where r.paymentRoutingStatus = :s", Long.class)
                .setParameter("s", status).getSingleResult();
    }

    /** Sum of line-item base amounts per month, for rows of (expenseDate, baseAmount, ...). */
    private List<TrendPoint> monthlyAmounts(List<Object[]> lines, int months) {
        Map<YearMonth, Object[]> byMonth = emptyMonths(months, () -> new Object[]{BigDecimal.ZERO, 0L});
        lines.forEach(row -> {
            Object[] slot = byMonth.get(YearMonth.from((LocalDate) row[0]));
            if (slot == null) return;
            slot[0] = ((BigDecimal) slot[0]).add(amount(row[1]));
            slot[1] = (long) slot[1] + 1;
        });
        return byMonth.entrySet().stream()
                .map(e -> new TrendPoint(e.getKey().toString(), monthLabel(e.getKey()), (BigDecimal) e.getValue()[0], (long) e.getValue()[1], 0))
                .toList();
    }

    /** Top categories by amount for rows of (expenseDate, baseAmount, categoryName); the tail folds into "Other". */
    private List<Slice> categoryRanking(List<Object[]> lines, int top) {
        Map<String, Slice> byCategory = new HashMap<>();
        lines.forEach(row -> {
            String name = row[2] != null ? (String) row[2] : "Uncategorized";
            Slice s = byCategory.getOrDefault(name, new Slice(name, name, 0, BigDecimal.ZERO));
            byCategory.put(name, new Slice(name, name, s.count() + 1, s.amount().add(amount(row[1]))));
        });
        return topSlices(byCategory.values(), top);
    }

    /** Rows of (name, count, sum) from a GROUP BY query. */
    private List<Slice> rankGrouped(List<Object[]> rows, int top) {
        return topSlices(rows.stream().map(row -> {
            String name = row[0] != null ? (String) row[0] : "Uncategorized";
            return new Slice(name, name, (Long) row[1], amount(row[2]));
        }).toList(), top);
    }

    private List<Slice> topSlices(Collection<Slice> slices, int top) {
        List<Slice> sorted = slices.stream().sorted(Comparator.comparing(Slice::amount).reversed()).toList();
        if (sorted.size() <= top) return sorted;
        List<Slice> result = new ArrayList<>(sorted.subList(0, top));
        List<Slice> tail = sorted.subList(top, sorted.size());
        result.add(new Slice("other", "Other (" + tail.size() + ")", tail.stream().mapToLong(Slice::count).sum(),
                tail.stream().map(Slice::amount).reduce(BigDecimal.ZERO, BigDecimal::add)));
        return result;
    }

    private List<Slice> statusSlices(List<ExpenseReport> reports, Map<UUID, BigDecimal> amounts) {
        Map<ReportStatus, List<ExpenseReport>> byStatus = reports.stream().collect(Collectors.groupingBy(ExpenseReport::getReportStatus));
        return byStatus.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<ReportStatus, List<ExpenseReport>> e) -> e.getValue().size()).reversed())
                .map(e -> new Slice(e.getKey().name(), statusLabel(e.getKey()), e.getValue().size(),
                        sum(e.getValue(), amounts, r -> true)))
                .toList();
    }

    /** Fixed waiting-time buckets, so every dashboard reads the same way. */
    private List<Slice> agingBuckets(List<LocalDateTime> since, LocalDateTime now) {
        long[] b = new long[4];
        since.forEach(t -> {
            long days = daysSince(t, now);
            b[days < 1 ? 0 : days <= 3 ? 1 : days <= 7 ? 2 : 3]++;
        });
        return List.of(
                new Slice("lt1", "Under 1 day", b[0], null),
                new Slice("1to3", "1–3 days", b[1], null),
                new Slice("4to7", "4–7 days", b[2], null),
                new Slice("gt7", "Over 7 days", b[3], null));
    }

    private static <T> Map<YearMonth, T> emptyMonths(int months, java.util.function.Supplier<T> init) {
        Map<YearMonth, T> map = new LinkedHashMap<>();
        YearMonth start = firstMonth(months);
        for (int i = 0; i < months; i++) map.put(start.plusMonths(i), init.get());
        return map;
    }

    private static YearMonth firstMonth(int months) {
        return YearMonth.now().minusMonths(months - 1L);
    }

    private static String monthLabel(YearMonth m) {
        return m.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    private static long daysSince(LocalDateTime t, LocalDateTime now) {
        return t == null ? 0 : Math.max(Duration.between(t, now).toDays(), 0);
    }

    private static BigDecimal amount(Object value) {
        return value instanceof BigDecimal b ? b : BigDecimal.ZERO;
    }

    private static long count(List<ExpenseReport> reports, Function<ExpenseReport, Boolean> test) {
        return reports.stream().filter(test::apply).count();
    }

    private static BigDecimal sum(List<ExpenseReport> reports, Map<UUID, BigDecimal> amounts, Function<ExpenseReport, Boolean> test) {
        return reports.stream().filter(test::apply)
                .map(r -> amounts.getOrDefault(r.getReportId(), BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Item item(ExpenseReport r, Map<UUID, BigDecimal> amounts, LocalDateTime at, String note) {
        return new Item(r.getReportId(), r.getReportNumber(), r.getTitle(), r.getEmployeeId(), r.getReportStatus().name(),
                amounts.getOrDefault(r.getReportId(), BigDecimal.ZERO), at, note);
    }

    private static String statusLabel(ReportStatus s) {
        return switch (s) {
            case DRAFT -> "Draft";
            case PENDING_APPROVAL -> "Pending approval";
            case PENDING_FINANCE_VERIFICATION -> "Finance check";
            case AWAITING_CORRECTION -> "Awaiting correction";
            case POLICY_REJECTED -> "Policy rejected";
            case QUERY_RAISED -> "Query raised";
            case APPROVED -> "Approved";
            case REJECTED -> "Rejected";
            case CANCELLED -> "Cancelled";
            case CLOSED -> "Closed";
            case REIMBURSED -> "Reimbursed";
            case SUBMITTED -> "Submitted";
        };
    }
}
