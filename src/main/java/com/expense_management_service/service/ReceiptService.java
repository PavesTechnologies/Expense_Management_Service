package com.expense_management_service.service;

import com.expense_management_service.dto.response.LateReceiptResponse;
import com.expense_management_service.dto.response.ReceiptResponse;
import com.expense_management_service.dto.response.ReceiptUrlResponse;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Receipt upload/retrieval/delete business logic (Amazon S3-backed).
 * <p>
 * A receipt belongs to its {@code ExpenseReport} from the moment it's uploaded (EP03-S4) — no
 * line item is required, since OCR/employee review is what determines a line item's fields.
 * Ownership (an Employee may only touch receipts on their own reports) and status-gating
 * (upload/delete only while the parent report is Draft/Policy Rejected/Query Raised) are
 * enforced inside the implementation — the caller is always taken from the security context,
 * never a request argument.
 */
public interface ReceiptService {

    ReceiptResponse upload(UUID reportId, MultipartFile file);

    /**
     * Uploads a receipt directly against an already-existing {@code ExpenseLineItem} — the Manual
     * Expense Entry flow, where the employee has already entered the line item's fields by hand
     * and is now attaching its receipt. Ownership/status-gating is enforced against the line
     * item's parent report, same as {@link #upload}.
     */
    ReceiptResponse uploadForLineItem(UUID lineItemId, MultipartFile file);

    List<ReceiptResponse> getAllForReport(UUID reportId);

    /** Retained for the (still-supported) line-item-scoped listing endpoint. */
    List<ReceiptResponse> getAllForLineItem(UUID lineItemId);

    ReceiptResponse getById(UUID receiptId);

    /** Time-limited URL for inline browser preview. */
    ReceiptUrlResponse getViewUrl(UUID receiptId);

    /** Time-limited URL that forces a download with the receipt's original file name. */
    ReceiptUrlResponse getDownloadUrl(UUID receiptId);

    void delete(UUID receiptId);

    /**
     * Attaches a receipt found after the report was approved, reimbursed or closed. It is linked to the original expense line
     * and never changes the line's amount, status or payment. Requires the actual receipt date and a reason.
     */
    LateReceiptResponse attachLate(UUID lineItemId, MultipartFile file, LocalDate actualReceiptDate, String reason);

    /** Late receipts on one report, visible to the owner, its approvers and Finance/AP. */
    List<LateReceiptResponse> getLateForReport(UUID reportId);

    /** Late receipts waiting for Finance/AP review, oldest first. */
    List<LateReceiptResponse> getPendingLateReceipts();

    /** Finance/AP verifies a late receipt. A note is required when the OCR total differs from the claimed amount. */
    LateReceiptResponse verifyLate(UUID receiptId, String note);

    /** Finance/AP rejects a late receipt. The receipt is kept as history; a note is required. */
    LateReceiptResponse rejectLate(UUID receiptId, String note);
}
