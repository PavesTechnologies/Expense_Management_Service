package com.expense_management_service.service;

import com.expense_management_service.dto.response.ReceiptResponse;
import com.expense_management_service.dto.response.ReceiptUrlResponse;
import org.springframework.web.multipart.MultipartFile;

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

    List<ReceiptResponse> getAllForReport(UUID reportId);

    /** Retained for the (still-supported) line-item-scoped listing endpoint. */
    List<ReceiptResponse> getAllForLineItem(UUID lineItemId);

    ReceiptResponse getById(UUID receiptId);

    /** Time-limited URL for inline browser preview. */
    ReceiptUrlResponse getViewUrl(UUID receiptId);

    /** Time-limited URL that forces a download with the receipt's original file name. */
    ReceiptUrlResponse getDownloadUrl(UUID receiptId);

    void delete(UUID receiptId);
}
