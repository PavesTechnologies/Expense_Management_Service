package com.expense_management_service.service;

import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.enums.DuplicateReceiptStatus;

import java.util.List;
import java.util.UUID;

/**
 * Stops the same bill being reimbursed twice — by the same employee on two reports, twice within
 * one report, or by two employees who shared the bill (each photographing it).
 * <p>
 * A receipt is "already claimed" when it is attached to a line item on a report that has been
 * submitted (any status except DRAFT, REJECTED or CANCELLED, any employee), or to a different line
 * item of the same report. Unattached receipts (an abandoned AI scan) never count.
 * <p>
 * Match strength:
 * <ul>
 *   <li>{@link MatchReason#SAME_FILE} — identical bytes (SHA-256). Blocking.</li>
 *   <li>{@link MatchReason#SAME_INVOICE} — OCR invoice number + supplier (GSTIN, else merchant)
 *       + date (or amount when a date is missing). Catches two photos of one bill. Blocking.</li>
 *   <li>{@link MatchReason#SAME_DETAILS} — OCR merchant + amount + currency + date. Could be two
 *       genuine purchases, so a warning the employee must explain, not a block.</li>
 * </ul>
 * Messages never name another employee or their report; they say "another employee's expense report".
 */
public interface ReceiptDuplicateService {

    enum MatchReason { SAME_FILE, SAME_INVOICE, SAME_DETAILS }

    record DuplicateMatch(
            UUID receiptId,
            String receiptFileName,
            UUID lineItemId,
            MatchReason reason,
            UUID otherReceiptId,
            UUID otherReportId,
            String otherReportNumber,
            boolean sameEmployee,
            boolean sameReport,
            String message
    ) {
        public boolean blocking() {
            return reason != MatchReason.SAME_DETAILS;
        }
    }

    record DuplicateSummary(DuplicateReceiptStatus status, String message) {
        public static final DuplicateSummary NONE = new DuplicateSummary(DuplicateReceiptStatus.NONE, null);
    }

    /** Every claimed receipt this one matches, strongest reason per other receipt. */
    List<DuplicateMatch> findMatches(Receipt receipt);

    /** Worst match for display on a receipt (upload response, receipt list, OCR result). */
    DuplicateSummary summarize(Receipt receipt);

    /** Non-blocking ({@link MatchReason#SAME_DETAILS}) matches for the receipts on one line item. */
    List<DuplicateMatch> findWarningMatchesForLineItem(UUID lineItemId);

    /** Blocking matches for every receipt attached to a line item on this report. */
    List<DuplicateMatch> findBlockingMatchesForReport(ExpenseReport report);
}
