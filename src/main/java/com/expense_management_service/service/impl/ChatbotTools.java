package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.ApprovalQueueItemResponse;
import com.expense_management_service.dto.response.CashAdvanceResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.ApprovalWorkflowService;
import com.expense_management_service.service.CashAdvanceService;
import com.expense_management_service.service.ExpenseReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The tools the Paves Assistant's LLM may call to read live data: expense data here, and the other intranet
 * modules (leave, timesheets, projects, celebrations) through {@link IntranetChatTools}.
 * <p>
 * Every tool reads <b>only the caller's own data</b> through the same services that back the
 * "My Expenses", "My Advances" and "Approvals" screens, with the caller taken from the JWT via
 * {@link CurrentUserService}. The model never supplies an employee id, so it cannot be talked into
 * reading someone else's records. All tools are read-only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatbotTools {

    static final String MY_EXPENSE_REPORTS = "get_my_expense_reports";
    static final String MY_CASH_ADVANCES = "get_my_cash_advances";
    static final String MY_PENDING_APPROVALS = "get_my_pending_approvals";

    /** Rows read for counts and totals (the API's max page size). */
    private static final int SUMMARY_ROWS = 100;
    /** Rows sent to the model in detail; kept small so requests stay inside free-tier token limits. */
    private static final int DETAIL_ROWS = 25;
    /** Rows listed for approvals. */
    private static final int MAX_ROWS = 25;

    /**
     * Optional filters are typed string-or-null: models often send {@code "status": null} for "no filter",
     * and providers that validate tool arguments against the schema (e.g. Groq) reject that as a plain string.
     */
    private static final List<String> OPTIONAL_STRING = List.of("string", "null");

    private final ExpenseReportService expenseReportService;
    private final CashAdvanceService cashAdvanceService;
    private final ApprovalWorkflowService approvalWorkflowService;
    private final CurrentUserService currentUserService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /** Tools for the other intranet modules; optional so expense-only setups (and tests) still work without it. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IntranetChatTools intranetTools;

    /** Tool definitions in the Ollama/OpenAI function-calling format. */
    public List<Map<String, Object>> definitions() {
        List<Map<String, Object>> all = new java.util.ArrayList<>(expenseDefinitions());
        if (intranetTools != null) all.addAll(intranetTools.definitions());
        return all;
    }

    private List<Map<String, Object>> expenseDefinitions() {
        return List.of(
                tool(MY_EXPENSE_REPORTS,
                        "List the current user's own expense reports (claims), newest first, with a summary of counts "
                                + "by status and the total paid to them, plus details per claim (status, amounts, dates, "
                                + "payment). Use for questions about 'my claims', 'my expenses', claim status, payments "
                                + "or reimbursement. A paid claim keeps status APPROVED and shows amountPaid/paidOn; "
                                + "questions about payments or totals need NO status filter.",
                        Map.of("status", Map.of(
                                "type", OPTIONAL_STRING,
                                "description", "Leave empty (null) unless the user asks for one specific workflow status. "
                                        + "One of: DRAFT, SUBMITTED, PENDING_APPROVAL, PENDING_FINANCE_VERIFICATION, "
                                        + "AWAITING_CORRECTION, QUERY_RAISED, POLICY_REJECTED, APPROVED, REJECTED, "
                                        + "CANCELLED, CLOSED. Never filter to find paid claims."))),
                tool(MY_CASH_ADVANCES,
                        "List the current user's own cash advances, newest first, with status, requested/approved/"
                                + "disbursed amounts, outstanding balance and settlement due date. Use for questions "
                                + "about 'my advances', advance balance or settlement.",
                        Map.of("status", Map.of(
                                "type", OPTIONAL_STRING,
                                "description", "Leave empty (null) unless the user asks for one specific status. "
                                        + "One of: DRAFT, SUBMITTED, PENDING_MANAGER_APPROVAL, "
                                        + "PENDING_COST_CENTER_APPROVAL, PENDING_FINANCE_APPROVAL, APPROVED, "
                                        + "DISBURSED, PARTIALLY_SETTLED, SETTLED, REJECTED, CLOSED"))),
                tool(MY_PENDING_APPROVALS,
                        "List the expense reports and cash advance requests waiting for the current user to "
                                + "approve (the user acting as a manager or approver). Use for 'what do I need to "
                                + "approve' or 'my pending approvals'.",
                        Map.of())
        );
    }

    /** Runs a tool and returns its result as JSON text for the model; failures come back as an error object. */
    public String execute(String name, Map<String, Object> arguments) {
        try {
            Object result = switch (name) {
                case MY_EXPENSE_REPORTS -> myExpenseReports(stringArg(arguments, "status"));
                case MY_CASH_ADVANCES -> myCashAdvances(stringArg(arguments, "status"));
                case MY_PENDING_APPROVALS -> myPendingApprovals();
                default -> intranetTools != null && intranetTools.handles(name)
                        ? intranetTools.execute(name, arguments)
                        : Map.of("error", "Unknown tool: " + name);
            };
            return objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            log.warn("[CHATBOT] tool {} failed: {}", name, ex.getMessage());
            return "{\"error\":\"This information could not be loaded right now.\"}";
        }
    }

    private Map<String, Object> myExpenseReports(String status) {
        PageResponse<ExpenseReportResponse> page = expenseReportService.getAll(
                PageRequest.of(0, SUMMARY_ROWS, Sort.by(Sort.Direction.DESC, "createdAt")), status, null);
        List<ExpenseReportResponse> all = page.content();

        // Totals are computed here over every fetched report, not left to the model: models add up
        // unreliably, and only the newest DETAIL_ROWS reports are sent in detail.
        Map<String, Long> byStatus = new TreeMap<>();
        Map<String, BigDecimal> paidByCurrency = new TreeMap<>();
        int paidCount = 0;
        for (ExpenseReportResponse r : all) {
            byStatus.merge(String.valueOf(r.reportStatus()), 1L, Long::sum);
            if (r.paymentConfirmation() != null && r.paymentConfirmation().amountPaid() != null) {
                paidCount++;
                paidByCurrency.merge(currencyOf(r.baseCurrencyCode()), r.paymentConfirmation().amountPaid(), BigDecimal::add);
            }
        }

        List<Map<String, Object>> rows = all.stream().limit(DETAIL_ROWS).map(r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", r.title());
            row.put("reportNumber", r.reportNumber());
            row.put("status", r.reportStatus());
            row.put("totalAmount", r.totalAmount());
            row.put("reimbursableAmount", r.reimbursableAmount());
            row.put("currency", r.baseCurrencyCode());
            row.put("createdOn", r.createdAt() == null ? null : r.createdAt().toLocalDate());
            row.put("submittedOn", r.submittedAt() == null ? null : r.submittedAt().toLocalDate());
            row.put("approvedOn", r.approvedAt() == null ? null : r.approvedAt().toLocalDate());
            row.put("submissionDeadline", r.submissionDeadline());
            if (r.policyWarningCount() > 0) row.put("policyWarnings", r.policyWarningCount());
            row.put("paymentStatus", r.paymentRoutingStatus());
            if (r.noPaymentDue()) row.put("noPaymentDue", true);
            if (r.paymentConfirmation() != null) {
                row.put("paidOn", r.paymentConfirmation().paymentDate());
                row.put("amountPaid", r.paymentConfirmation().amountPaid());
            }
            return withoutNulls(row);
        }).toList();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalReports", page.totalElements());
        summary.put("reportsByStatus", byStatus);
        summary.put("paidReports", paidCount);
        summary.put("totalPaidToYouByCurrency", paidByCurrency);
        return result(summary, page.totalElements(), all.size(), "reports", rows);
    }

    private Map<String, Object> myCashAdvances(String status) {
        Page<CashAdvanceResponse> page = cashAdvanceService.getMyAdvances(
                currentUserService.getEmployeeId(), status,
                PageRequest.of(0, SUMMARY_ROWS, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<CashAdvanceResponse> all = page.getContent();

        Map<String, Long> byStatus = new TreeMap<>();
        Map<String, BigDecimal> balanceWithYou = new TreeMap<>();
        for (CashAdvanceResponse a : all) {
            String s = String.valueOf(a.status()).toUpperCase();
            byStatus.merge(s, 1L, Long::sum);
            if (holdsMoney(s) && a.outstandingBalance() != null && a.outstandingBalance().signum() > 0) {
                balanceWithYou.merge(currencyOf(a.currencyCode()), a.outstandingBalance(), BigDecimal::add);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalAdvances", page.getTotalElements());
        summary.put("advancesByStatus", byStatus);
        summary.put("unsettledBalanceWithYouByCurrency", balanceWithYou);
        summary.put("note", "unsettledBalanceWithYou counts only advances already paid out to the employee; "
                + "an advance still awaiting approval has not been paid, so nothing is owed on it yet.");
        return result(summary, page.getTotalElements(), all.size(), "advances",
                all.stream().limit(DETAIL_ROWS).map(this::advanceRow).toList());
    }

    /** Wraps a tool result, telling the model plainly when the detail list is not the whole set. */
    private static Map<String, Object> result(Map<String, Object> summary, long total, int fetched,
                                              String listName, List<Map<String, Object>> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        if (rows.size() < total) {
            out.put("detailNote", "Details below cover only the newest " + rows.size() + " of " + total
                    + ". Use the summary for counts and totals" + (fetched < total
                    ? " (the summary covers the newest " + fetched + "; say the figures may be incomplete)." : "."));
        }
        out.put(listName, rows);
        return out;
    }

    /** An advance whose money is with the employee: paid out and not yet fully settled, closed or refused. */
    private static boolean holdsMoney(String status) {
        return !status.startsWith("PENDING")
                && !List.of("DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "CANCELLED", "CLOSED", "SETTLED").contains(status);
    }

    private static String currencyOf(String code) {
        return code == null || code.isBlank() ? "UNKNOWN" : code;
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> row) {
        row.values().removeIf(java.util.Objects::isNull);
        return row;
    }

    private Map<String, Object> myPendingApprovals() {
        String employeeId = currentUserService.getEmployeeId();
        PageResponse<ApprovalQueueItemResponse> reports =
                approvalWorkflowService.getMyQueue(employeeId, PageRequest.of(0, MAX_ROWS));
        Page<CashAdvanceResponse> advances =
                cashAdvanceService.getMyApprovals(employeeId, null, PageRequest.of(0, MAX_ROWS));

        List<Map<String, Object>> reportRows = reports.content().stream().map(q -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reportNumber", q.reportNumber());
            row.put("employeeId", q.employeeId());
            row.put("totalAmount", q.totalAmount());
            row.put("currency", q.currencyCode());
            row.put("status", q.reportStatus());
            row.put("submittedAt", q.submittedAt());
            row.put("approvalLevel", q.levelOrder());
            return row;
        }).toList();

        return Map.of(
                "expenseReportsAwaitingMe", reports.totalElements(),
                "expenseReports", reportRows,
                "cashAdvancesAwaitingMe", advances.getTotalElements(),
                "cashAdvances", advances.getContent().stream().map(this::advanceRow).toList());
    }

    private Map<String, Object> advanceRow(CashAdvanceResponse a) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", a.title());
        row.put("advanceNumber", a.advanceNumber());
        row.put("employeeId", a.employeeId());
        row.put("status", a.status());
        row.put("requestedAmount", a.requestedAmount() != null ? a.requestedAmount() : a.amount());
        row.put("approvedAmount", a.approvedAmount());
        row.put("disbursedAmount", a.disbursedAmount());
        row.put("outstandingBalance", a.outstandingBalance());
        row.put("currency", a.currencyCode());
        row.put("neededByDate", a.neededByDate());
        row.put("settlementDueDate", a.settlementDueDate());
        row.put("rejectionReason", a.rejectionReason());
        return withoutNulls(row);
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties) {
        return Map.of("type", "function", "function", Map.of(
                "name", name,
                "description", description,
                "parameters", Map.of("type", "object", "properties", properties, "required", List.of())));
    }

    private static String stringArg(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) return null;
        String text = value.toString().trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text.toUpperCase();
    }
}
