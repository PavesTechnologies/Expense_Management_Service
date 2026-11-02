package com.expense_management_service.service.impl;

import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.integration.ums.UmsClient;
import com.expense_management_service.integration.ums.dto.UmsUserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Queues the cash advance emails through the outbox: the manager and the employee at submission, the cost center owner
 * once the manager approves, and Finance when the request reaches its stage. A mail problem is logged and never blocks
 * the approval itself.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashAdvanceEmailService {

    static final String EMAIL_MANAGER_REQUEST = "CASH_ADVANCE_MANAGER_REQUEST";
    static final String EMAIL_EMPLOYEE_SUBMITTED = "CASH_ADVANCE_EMPLOYEE_SUBMITTED";
    static final String EMAIL_COST_CENTER_REQUEST = "CASH_ADVANCE_COST_CENTER_REQUEST";
    static final String EMAIL_FINANCE_REQUEST = "CASH_ADVANCE_FINANCE_REQUEST";

    private static final String APPROVALS_PATH = "/expense-management/approvals";
    private static final String MY_ADVANCES_PATH = "/expense-management/cash-advance/my";
    private static final String FINANCE_PATH = "/expense-management/finance";

    private final UmsClient umsClient;
    private final EmailOutboxService emailOutbox;

    /** Finance's shared mailbox for the Finance-stage request. Blank means the Finance mail is not sent. */
    @Value("${expense.mail.finance-address:}")
    private String financeAddress;

    /** Base URL of the portal, used for the links in the emails. Blank means the links are left out. */
    @Value("${expense.mail.app-base-url:}")
    private String appBaseUrl;

    /** Manager gets the request to act on; the employee gets the confirmation. */
    public void onSubmitted(CashAdvance advance) {
        Map<String, UmsUserResponse> users = usersById();
        String employeeName = nameOf(users, advance.getEmployeeId());
        String managerName = nameOf(users, advance.getManagerId());
        String advanceRef = String.valueOf(advance.getAdvanceId());

        mailOf(users, advance.getManagerId()).ifPresentOrElse(
                mail -> emailOutbox.enqueue(mail,
                        CashAdvanceEmailTemplate.managerSubject(advance, employeeName),
                        CashAdvanceEmailTemplate.managerBody(advance, employeeName, managerName, link(APPROVALS_PATH)),
                        EMAIL_MANAGER_REQUEST, advanceRef),
                () -> log.warn("Cash advance {}: manager {} has no email address, request mail not sent",
                        advanceRef, advance.getManagerId()));

        mailOf(users, advance.getEmployeeId()).ifPresentOrElse(
                mail -> emailOutbox.enqueue(mail,
                        CashAdvanceEmailTemplate.employeeSubject(advance),
                        CashAdvanceEmailTemplate.employeeBody(advance, employeeName, managerName, link(MY_ADVANCES_PATH)),
                        EMAIL_EMPLOYEE_SUBMITTED, advanceRef),
                () -> log.warn("Cash advance {}: employee {} has no email address, confirmation not sent",
                        advanceRef, advance.getEmployeeId()));
    }

    /** Sent once the manager has approved and the request is with the cost center owner. */
    public void onCostCenterStage(CashAdvance advance) {
        String ownerId = advance.getCostCenter() == null ? null : advance.getCostCenter().getOwnerEmployeeId();
        String advanceRef = String.valueOf(advance.getAdvanceId());
        if (ownerId == null || ownerId.isBlank()) {
            log.warn("Cash advance {}: cost center has no owner, cost center mail not sent", advanceRef);
            return;
        }
        Map<String, UmsUserResponse> users = usersById();
        String employeeName = nameOf(users, advance.getEmployeeId());
        String ownerName = nameOf(users, ownerId);
        String managerName = nameOf(users, advance.getManagerId());

        mailOf(users, ownerId).ifPresentOrElse(
                mail -> emailOutbox.enqueue(mail,
                        CashAdvanceEmailTemplate.costCenterSubject(advance),
                        CashAdvanceEmailTemplate.costCenterBody(advance, employeeName, ownerName, managerName, link(APPROVALS_PATH)),
                        EMAIL_COST_CENTER_REQUEST, advanceRef),
                () -> log.warn("Cash advance {}: cost center owner {} has no email address, mail not sent", advanceRef, ownerId));
    }

    /** Sent when the request reaches Finance's own stage. Finance gets nothing at submission. */
    public void onFinanceStage(CashAdvance advance) {
        String advanceRef = String.valueOf(advance.getAdvanceId());
        if (financeAddress == null || financeAddress.isBlank()) {
            log.warn("Cash advance {}: expense.mail.finance-address is not set, Finance mail not sent", advanceRef);
            return;
        }
        String employeeName = nameOf(usersById(), advance.getEmployeeId());
        emailOutbox.enqueue(financeAddress,
                CashAdvanceEmailTemplate.financeSubject(advance, employeeName),
                CashAdvanceEmailTemplate.financeBody(advance, employeeName, link(FINANCE_PATH)),
                EMAIL_FINANCE_REQUEST, advanceRef);
    }

    private Map<String, UmsUserResponse> usersById() {
        Map<String, UmsUserResponse> byId = new HashMap<>();
        for (UmsUserResponse user : umsClient.getAllUsers()) {
            if (user.userId() != null) {
                byId.put(String.valueOf(user.userId()), user);
            }
        }
        return byId;
    }

    private static Optional<String> mailOf(Map<String, UmsUserResponse> users, String employeeId) {
        if (employeeId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(users.get(employeeId.trim()))
                .map(UmsUserResponse::mail)
                .filter(mail -> !mail.isBlank());
    }

    private static String nameOf(Map<String, UmsUserResponse> users, String employeeId) {
        if (employeeId == null) {
            return "-";
        }
        UmsUserResponse user = users.get(employeeId.trim());
        if (user == null) {
            return employeeId;
        }
        String last = user.lastName() == null ? "" : " " + user.lastName();
        return (user.firstName() + last).trim();
    }

    private String link(String path) {
        return appBaseUrl == null || appBaseUrl.isBlank() ? null : appBaseUrl.replaceAll("/+$", "") + path;
    }
}
