package com.expense_management_service.service.impl;

import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.SalaryDeductionRequest;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
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

    static String requestBody(SalaryDeductionRequest request, CashAdvance advance, String employeeName,
                              BigDecimal outstandingBalance, String deductionComponent) {
        String currency = advance.getCurrency() != null ? advance.getCurrency().getCurrencyCode() : "";
        return String.join("\n",
                "Dear Payroll Team,",
                "",
                "Finance is requesting a salary deduction for the outstanding cash advance detailed below.",
                "",
                "EMPLOYEE DETAILS",
                "- Employee Name: " + employeeName,
                "- Employee ID: " + request.getEmployeeId(),
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
}
