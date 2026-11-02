package com.expense_management_service.service.impl;

import com.expense_management_service.entity.CashAdvance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Plain-text templates for the cash advance emails: submission (manager and employee), cost center owner and Finance.
 * Written in the same style as the salary deduction mails, with the employee block as a table.
 */
final class CashAdvanceEmailTemplate {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private CashAdvanceEmailTemplate() {
    }

    static String managerSubject(CashAdvance advance, String employeeName) {
        return "Action required: Cash advance request " + advance.getAdvanceId() + " from " + employeeName
                + ", " + amount(advance);
    }

    static String managerBody(CashAdvance advance, String employeeName, String managerName, String approvalLink) {
        return String.join("\n",
                "Dear " + managerName + ",",
                "",
                employeeName + " has submitted a cash advance request that needs your approval.",
                "",
                "EMPLOYEE DETAILS",
                employeeTable(advance, employeeName),
                "",
                requestDetails(advance),
                "",
                "ACTION REQUIRED",
                "Review and approve or reject the request" + linkSuffix(approvalLink),
                "",
                "Regards,",
                "Expense Management",
                "");
    }

    static String employeeSubject(CashAdvance advance) {
        return "Your cash advance request " + advance.getAdvanceId() + " was submitted";
    }

    static String employeeBody(CashAdvance advance, String employeeName, String nextApproverName, String trackingLink) {
        return String.join("\n",
                "Dear " + employeeName + ",",
                "",
                "Your cash advance request has been submitted for approval.",
                "",
                "EMPLOYEE DETAILS",
                employeeTable(advance, employeeName),
                "",
                requestDetails(advance),
                "",
                "- Next approver: " + nextApproverName,
                "",
                "You can track the request" + linkSuffix(trackingLink),
                "",
                "Regards,",
                "Expense Management",
                "");
    }

    static String costCenterSubject(CashAdvance advance) {
        return "Budget approval needed: cash advance " + advance.getAdvanceId() + " for " + costCenterName(advance);
    }

    static String costCenterBody(CashAdvance advance, String employeeName, String ownerName, String managerName,
                                 String approvalLink) {
        return String.join("\n",
                "Dear " + ownerName + ",",
                "",
                "A cash advance request for your cost center has been approved by the reporting manager and needs your approval.",
                "",
                "EMPLOYEE DETAILS",
                employeeTable(advance, employeeName),
                "",
                requestDetails(advance),
                "",
                "- Approved by manager: " + managerName,
                "",
                "ACTION REQUIRED",
                "Review the budget and approve or reject the request" + linkSuffix(approvalLink),
                "",
                "Regards,",
                "Expense Management",
                "");
    }

    static String financeSubject(CashAdvance advance, String employeeName) {
        return "Finance approval needed: cash advance " + advance.getAdvanceId() + " from " + employeeName;
    }

    static String financeBody(CashAdvance advance, String employeeName, String approvalLink) {
        return String.join("\n",
                "Dear Finance Team,",
                "",
                "A cash advance request has reached the Finance stage and needs your approval.",
                "",
                "EMPLOYEE DETAILS",
                employeeTable(advance, employeeName),
                "",
                requestDetails(advance),
                "",
                "ACTION REQUIRED",
                "Review the request, record the disbursement once approved" + linkSuffix(approvalLink),
                "",
                "Regards,",
                "Expense Management",
                "");
    }

    /** The employee block, shared by every cash advance email. */
    private static String employeeTable(CashAdvance advance, String employeeName) {
        return SalaryDeductionEmailTemplate.infoTable(List.of(
                new String[]{"Field", "Value"},
                new String[]{"Employee Name", employeeName},
                new String[]{"Employee ID", advance.getEmployeeId() == null ? "-" : advance.getEmployeeId()},
                new String[]{"Advance ID", String.valueOf(advance.getAdvanceId())},
                new String[]{"Title", blank(advance.getTitle())},
                new String[]{"Cost Center", costCenterName(advance)}));
    }

    private static String requestDetails(CashAdvance advance) {
        return String.join("\n",
                "REQUEST DETAILS",
                "- Amount requested: " + amount(advance),
                "- Purpose: " + blank(advance.getPurpose()),
                "- Needed by: " + date(advance.getNeededByDate()),
                "- Expected trip: " + date(advance.getExpectedTripStartDate()) + " to " + date(advance.getExpectedTripEndDate()),
                "- Settlement expected by: " + date(advance.getSettlementDueDate()));
    }

    private static String amount(CashAdvance advance) {
        String currency = advance.getCurrency() != null ? advance.getCurrency().getCurrencyCode() : "";
        BigDecimal value = advance.getAmount() == null ? BigDecimal.ZERO : advance.getAmount();
        return currency + " " + value.toPlainString();
    }

    private static String costCenterName(CashAdvance advance) {
        return advance.getCostCenter() == null ? "-" : blank(advance.getCostCenter().getCostCenterName());
    }

    private static String date(LocalDate value) {
        return value == null ? "-" : value.format(DATE);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String linkSuffix(String link) {
        return link == null || link.isBlank() ? "." : ": " + link;
    }
}
