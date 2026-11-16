package com.expense_management_service.service;

import com.expense_management_service.dto.response.ApprovalQueueItemResponse;
import com.expense_management_service.dto.response.ApprovalRecordResponse;
import com.expense_management_service.dto.response.CashAdvanceResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.repository.ExpenseReportRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The approver's expense reports and cash advances as one list, newest first, paged on the server.
 *
 * <p>Both sources are the approver's own records, scoped by the same services the Pending and history screens use,
 * so this list cannot show anything they would not show. The merge happens over the complete set for that approver,
 * then the page is cut, so every record appears exactly once and no page overlaps or skips another.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ApprovalRecordsService {

    /** Upper bound for one approver's records in a single merge. Each approver's queue and history are far smaller. */
    static final int ALL_RECORDS = 10_000;

    static final Set<String> CASH_PENDING = Set.of(
            "SUBMITTED", "PENDING", "PENDING_APPROVAL", "PENDING_MANAGER_APPROVAL",
            "PENDING_COST_CENTER_APPROVAL", "PENDING_COST_CENTER", "PENDING_FINANCE_APPROVAL", "PENDING_FINANCE");
    static final Set<String> CASH_APPROVED = Set.of("APPROVED", "DISBURSED", "SETTLED");
    static final Set<String> CASH_REJECTED = Set.of("REJECTED");

    private final ApprovalWorkflowService approvalWorkflowService;
    private final CashAdvanceService cashAdvanceService;
    private final ExpenseReportRepository expenseReportRepository;

    /**
     * @param tab         {@code all}, {@code pending}, {@code approved} or {@code rejected}
     * @param includeCash whether the caller's role may see cash advances (the cash approvals list checks the same)
     * @param search      optional text matched against reference, title, employee and status
     */
    public PageResponse<ApprovalRecordResponse> page(String actingEmployeeId, boolean includeCash, String tab,
                                                     String search, int page, int size) {
        String t = tab == null ? "all" : tab.trim().toLowerCase(Locale.ROOT);
        boolean all = t.equals("all");
        List<ApprovalRecordResponse> rows = new ArrayList<>();

        if (all || t.equals("pending")) {
            rows.addAll(pendingExpenses(actingEmployeeId));
        }
        if (all || t.equals("approved")) {
            rows.addAll(historyExpenses(actingEmployeeId, "APPROVED"));
        }
        if (all || t.equals("rejected")) {
            rows.addAll(historyExpenses(actingEmployeeId, "REJECTED"));
        }
        if (includeCash) {
            rows.addAll(cashRecords(actingEmployeeId, t));
        }

        String needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        Map<String, ApprovalRecordResponse> unique = new LinkedHashMap<>();
        for (ApprovalRecordResponse r : rows) {
            if (!needle.isEmpty() && !matches(r, needle)) {
                continue;
            }
            unique.putIfAbsent(r.type() + ":" + r.id(), r);
        }
        List<ApprovalRecordResponse> sorted = new ArrayList<>(unique.values());
        sorted.sort(Comparator.comparing(ApprovalRecordResponse::date,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(r -> r.id().toString()));

        return slice(sorted, page, size);
    }

    static PageResponse<ApprovalRecordResponse> slice(List<ApprovalRecordResponse> sorted, int page, int size) {
        int safeSize = Math.max(1, size);
        int safePage = Math.max(0, page);
        int total = sorted.size();
        int totalPages = (int) Math.ceil(total / (double) safeSize);
        int from = Math.min(safePage * safeSize, total);
        int to = Math.min(from + safeSize, total);
        return new PageResponse<>(sorted.subList(from, to), safePage, safeSize, total, totalPages,
                safePage == 0, safePage >= totalPages - 1);
    }

    private boolean matches(ApprovalRecordResponse r, String needle) {
        return contains(r.reference(), needle) || contains(r.title(), needle)
                || contains(r.employeeId(), needle) || contains(r.status(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private List<ApprovalRecordResponse> pendingExpenses(String actingEmployeeId) {
        List<ApprovalQueueItemResponse> queue = approvalWorkflowService
                .getMyQueue(actingEmployeeId, PageRequest.of(0, ALL_RECORDS)).content();
        List<UUID> ids = queue.stream().map(ApprovalQueueItemResponse::reportId).toList();
        Map<UUID, String> titles = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            for (ExpenseReport report : expenseReportRepository.findAllById(ids)) {
                titles.put(report.getReportId(), report.getTitle());
            }
        }
        return queue.stream().map(item -> new ApprovalRecordResponse(
                ApprovalRecordResponse.EXPENSE,
                item.reportId(),
                item.reportNumber(),
                item.employeeId(),
                titles.get(item.reportId()),
                "PENDING_APPROVAL",
                item.totalAmount(),
                item.currencyCode(),
                item.submittedAt())).toList();
    }

    private List<ApprovalRecordResponse> historyExpenses(String actingEmployeeId, String outcome) {
        List<ExpenseReportResponse> history = approvalWorkflowService
                .getMyHistory(actingEmployeeId, outcome, PageRequest.of(0, ALL_RECORDS)).content();
        return history.stream().map(report -> new ApprovalRecordResponse(
                ApprovalRecordResponse.EXPENSE,
                report.reportId(),
                report.reportNumber(),
                report.employeeId(),
                report.title(),
                report.reportStatus(),
                report.totalAmount(),
                report.currencyCode(),
                report.submittedAt() != null ? report.submittedAt() : report.createdAt())).toList();
    }

    private List<ApprovalRecordResponse> cashRecords(String actingEmployeeId, String tab) {
        List<CashAdvanceResponse> advances = cashAdvanceService
                .getMyApprovals(actingEmployeeId, null, PageRequest.of(0, ALL_RECORDS)).getContent();
        return advances.stream()
                .filter(a -> statusInTab(a.status(), tab))
                .map(a -> new ApprovalRecordResponse(
                        ApprovalRecordResponse.CASH_ADVANCE,
                        a.advanceId(),
                        shortReference(a.advanceId()),
                        a.employeeId(),
                        a.title() != null && !a.title().isBlank() ? a.title() : a.purpose(),
                        a.status(),
                        a.amount(),
                        a.currencyCode(),
                        a.createdAt())).toList();
    }

    private static boolean statusInTab(String status, String tab) {
        String s = status == null ? "" : status.toUpperCase(Locale.ROOT);
        return switch (tab) {
            case "pending" -> CASH_PENDING.contains(s);
            case "approved" -> CASH_APPROVED.contains(s);
            case "rejected" -> CASH_REJECTED.contains(s);
            default -> true;
        };
    }

    public static String shortReference(UUID advanceId) {
        return advanceId == null ? "" : "#" + advanceId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
