package com.expense_management_service.service.impl;

import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.SalaryDeductionRequest;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Plain-text templates for the salary deduction emails to the payroll team. Every value comes from stored data.
 * Nothing in these emails is an authorization ID, and no payroll reference is ever generated here.
 */
final class SalaryDeductionEmailTemplate {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private SalaryDeductionEmailTemplate() {
    }

    static String requestSubject(SalaryDeductionRequest request, String employeeName) {
        return "Salary Deduction Request - " + request.getPayrollRequestId() + " - " + employeeName;
    }

    /** Reply subject, so the confirmation sits in the same thread as the request email. */
    static String confirmationSubject(SalaryDeductionRequest request, String employeeName) {
        return "Re: " + requestSubject(request, employeeName);
    }

    static String confirmationBody(SalaryDeductionRequest request, CashAdvance advance, String employeeName,
                                   BigDecimal actualDeducted, String confirmedBy) {
        String currency = advance.getCurrency() != null ? advance.getCurrency().getCurrencyCode() : "";
        BigDecimal shortfall = request.getRequestedAmount().subtract(actualDeducted);
        return String.join("\n",
                "Dear Payroll Team,",
                "",
                "Finance has recorded the Payroll result for the salary deduction request below.",
                "",
                "- Payroll Request ID: " + request.getPayrollRequestId(),
                employeeTable(employeeName, request.getEmployeeId()),
                "",
                "- Cash Advance ID: " + advance.getAdvanceId(),
                "- Payroll Month: " + request.getPayrollMonth(),
                "- Requested Deduction Amount: " + request.getRequestedAmount().toPlainString() + " " + currency,
                "- Actual Amount Deducted: " + actualDeducted.toPlainString() + " " + currency,
                "- Shortfall: " + shortfall.toPlainString() + " " + currency,
                "- Payroll Reference: " + (request.getPayrollReference() == null ? "-" : request.getPayrollReference()),
                "- Recorded By: " + confirmedBy,
                "",
                "No action is needed unless the figures above do not match your records. If they differ, please reply quoting the Payroll Request ID.",
                "",
                "Regards,",
                "Finance Team",
                "");
    }

    static String requestBody(SalaryDeductionRequest request, CashAdvance advance, String employeeName,
                              BigDecimal outstandingBalance, String deductionComponent) {
        String currency = advance.getCurrency() != null ? advance.getCurrency().getCurrencyCode() : "";
        return String.join("\n",
                "Dear Payroll Team,",
                "",
                "Finance is requesting a salary deduction for the outstanding cash advance detailed below.",
                "",
                "EMPLOYEE DETAILS",
                employeeTable(employeeName, request.getEmployeeId()),
                "",
                "CASH ADVANCE DETAILS",
                "- Cash Advance ID: " + advance.getAdvanceId(),
                "- Original Advance Amount: " + advance.getAmount().toPlainString() + " " + currency,
                "- Outstanding Balance: " + outstandingBalance.toPlainString() + " " + currency,
                "",
                "DEDUCTION REQUEST",
                "- Payroll Request ID: " + request.getPayrollRequestId(),
                "- Payroll Component: " + deductionComponent + " (deduction)",
                "- Requested Deduction Amount: " + request.getRequestedAmount().toPlainString() + " " + currency,
                "- Payroll Month: " + request.getPayrollMonth(),
                "- Request Initiated By: " + request.getRequestedBy(),
                "- Request Date: " + request.getRequestedAt().format(DATE),
                "",
                "ACTION REQUIRED",
                "Please add the \"" + deductionComponent + "\" deduction component to the employee's payslip for the payroll "
                        + "month above, process payroll, and confirm the amount actually deducted.",
                "",
                "If the requested amount cannot be deducted in full, please confirm the actual amount deducted and the "
                        + "remaining balance or reason for the shortfall.",
                "",
                "Please quote the Payroll Request ID in your reply so Finance can reconcile the request.",
                "",
                "Regards,",
                "Finance Team",
                "");
    }

    /** The employee block as a plain-text table, since the mail is sent as text with no HTML. */
    static String employeeTable(String employeeName, String employeeId) {
        return infoTable(List.of(
                new String[]{"Field", "Value"},
                new String[]{"Employee Name", employeeName},
                new String[]{"Employee ID", employeeId == null ? "-" : employeeId}));
    }

    /** Renders two-column rows as an aligned ASCII table. The first row is the header. */
    static String infoTable(List<String[]> rows) {
        int keyWidth = rows.stream().mapToInt(r -> r[0].length()).max().orElse(0);
        int valueWidth = rows.stream().mapToInt(r -> r[1].length()).max().orElse(0);
        String border = "+-" + "-".repeat(keyWidth) + "-+-" + "-".repeat(valueWidth) + "-+";
        StringBuilder table = new StringBuilder(border).append('\n');
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            table.append("| ").append(pad(row[0], keyWidth)).append(" | ").append(pad(row[1], valueWidth)).append(" |\n");
            if (i == 0) {
                table.append(border).append('\n');
            }
        }
        return table.append(border).toString();
    }

    private static String pad(String value, int width) {
        return value + " ".repeat(Math.max(0, width - value.length()));
    }
}
