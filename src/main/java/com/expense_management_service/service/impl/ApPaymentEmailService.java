package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ExpenseReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Tells AP by email that a report is approved for payment and what is owed. Queued through the outbox, so a mail
 * problem never blocks the approval. Sent only when {@code expense.mail.ap-address} is configured.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApPaymentEmailService {

    static final String EMAIL_AP_PAYMENT_DUE = "AP_PAYMENT_DUE";

    private static final String AP_QUEUE_PATH = "/expense-management/ap-payments/queue";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final EmailOutboxService emailOutbox;

    /** AP mailbox for payment notices. Blank means the AP mail is not sent. */
    @Value("${expense.mail.ap-address:}")
    private String apAddress;

    /** Base URL of the portal, used for the link in the email. Blank means the link is left out. */
    @Value("${expense.mail.app-base-url:}")
    private String appBaseUrl;

    public void onApprovedForPayment(ExpenseReport report, String ownerName, String amountOwedText) {
        String reportNumber = report.getReportNumber();
        if (apAddress == null || apAddress.isBlank()) {
            log.warn("Report {}: expense.mail.ap-address is not set, AP payment mail not sent", reportNumber);
            return;
        }
        emailOutbox.enqueue(apAddress,
                "Payment due: " + reportNumber + " - " + ownerName + " - " + amountOwedText,
                body(report, ownerName, amountOwedText),
                EMAIL_AP_PAYMENT_DUE, reportNumber);
    }

    private String body(ExpenseReport report, String ownerName, String amountOwedText) {
        String trip = report.getTripStartDate() == null || report.getTripEndDate() == null
                ? "-"
                : report.getTripStartDate().format(DATE) + " to " + report.getTripEndDate().format(DATE);
        String queue = appBaseUrl == null || appBaseUrl.isBlank()
                ? null
                : appBaseUrl.replaceAll("/+$", "") + AP_QUEUE_PATH;
        return String.join("\n",
                "Dear Accounts Payable Team,",
                "",
                "Finance has verified the expense report below and approved it for payment.",
                "",
                "PAYMENT DETAILS",
                "+-----------------+--------------------------------+",
                "| Field           | Value                          |",
                "+-----------------+--------------------------------+",
                "| Report #        | " + pad(report.getReportNumber(), 30) + " |",
                "| Employee        | " + pad(ownerName, 30) + " |",
                "| Trip            | " + pad(trip, 30) + " |",
                "| Amount owed     | " + pad(amountOwedText, 30) + " |",
                "+-----------------+--------------------------------+",
                "",
                "ACTION REQUIRED",
                "Pay the amount above to the employee and record the payment reference in the AP queue"
                        + (queue == null ? "." : ": " + queue),
                "",
                "The report is not marked paid until the payment is recorded.",
                "",
                "Regards,",
                "Expense Management",
                "");
    }

    private static String pad(String value, int width) {
        String v = value == null ? "-" : value;
        return v.length() >= width ? v.substring(0, width) : v + " ".repeat(width - v.length());
    }
}
