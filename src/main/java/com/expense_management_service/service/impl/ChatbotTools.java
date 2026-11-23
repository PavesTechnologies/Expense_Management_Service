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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools the Paves Assistant's LLM may call to read live expense data.
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

    private static final int MAX_ROWS = 10;

    private final ExpenseReportService expenseReportService;
    private final CashAdvanceService cashAdvanceService;
    private final ApprovalWorkflowService approvalWorkflowService;
    private final CurrentUserService currentUserService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /** Tool definitions in the Ollama/OpenAI function-calling format. */
    public List<Map<String, Object>> definitions() {
        return List.of(
                tool(MY_EXPENSE_REPORTS,
                        "List the current user's own expense reports (claims), newest first, with status, amounts, "
                                + "submission and approval dates and payment details. Use for questions about "
                                + "'my claims', 'my expenses', claim status or reimbursement.",
                        Map.of("status", Map.of(
                                "type", "string",
                                "description", "Optional filter. One of: DRAFT, SUBMITTED, PENDING_APPROVAL, "
                                        + "PENDING_FINANCE_VERIFICATION, AWAITING_CORRECTION, QUERY_RAISED, "
                                        + "POLICY_REJECTED, APPROVED, REJECTED, CANCELLED, CLOSED, REIMBURSED"))),
                tool(MY_CASH_ADVANCES,
                        "List the current user's own cash advances, newest first, with status, requested/approved/"
                                + "disbursed amounts, outstanding balance and settlement due date. Use for questions "
                                + "about 'my advances', advance balance or settlement.",
                        Map.of("status", Map.of(
                                "type", "string",
                                "description", "Optional filter. One of: DRAFT, SUBMITTED, PENDING_MANAGER_APPROVAL, "
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
                default -> Map.of("error", "Unknown tool: " + name);
            };
            return objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            log.warn("[CHATBOT] tool {} failed: {}", name, ex.getMessage());
            return "{\"error\":\"This information could not be loaded right now.\"}";
        }
    }

    private Map<String, Object> myExpenseReports(String status) {
        PageResponse<ExpenseReportResponse> page = expenseReportService.getAll(
                PageRequest.of(0, MAX_ROWS, Sort.by(Sort.Direction.DESC, "createdAt")), status, null);
        List<Map<String, Object>> rows = page.content().stream().map(r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reportNumber", r.reportNumber());
            row.put("title", r.title());
            row.put("status", r.reportStatus());
            row.put("totalAmount", r.totalAmount());
            row.put("reimbursableAmount", r.reimbursableAmount());
            row.put("currency", r.baseCurrencyCode());
            row.put("lineItemCount", r.lineItemCount());
            row.put("createdAt", r.createdAt());
            row.put("submittedAt", r.submittedAt());
            row.put("approvedAt", r.approvedAt());
            row.put("submissionDeadline", r.submissionDeadline());
            row.put("policyWarningCount", r.policyWarningCount());
            row.put("paymentStatus", r.paymentRoutingStatus());
            row.put("noPaymentDue", r.noPaymentDue());
            if (r.paymentConfirmation() != null) {
                row.put("paidOn", r.paymentConfirmation().paymentDate());
                row.put("amountPaid", r.paymentConfirmation().amountPaid());
                row.put("paymentReference", r.paymentConfirmation().paymentReference());
            }
            return row;
        }).toList();
        return Map.of("totalReports", page.totalElements(), "showing", rows.size(), "reports", rows);
    }

    private Map<String, Object> myCashAdvances(String status) {
        Page<CashAdvanceResponse> page = cashAdvanceService.getMyAdvances(
                currentUserService.getEmployeeId(), status,
                PageRequest.of(0, MAX_ROWS, Sort.by(Sort.Direction.DESC, "createdAt")));
        return Map.of("totalAdvances", page.getTotalElements(), "showing", page.getNumberOfElements(),
                "advances", page.getContent().stream().map(this::advanceRow).toList());
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
        row.put("advanceNumber", a.advanceNumber());
        row.put("title", a.title());
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
        return row;
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
