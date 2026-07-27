package com.expense_management_service.service;

import com.expense_management_service.dto.response.ReceiptResponse;
import com.expense_management_service.dto.response.ReceiptUrlResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Receipt upload/retrieval/delete business logic (Amazon S3-backed, V1).
 * <p>
 * Ownership (an Employee may only touch receipts on their own line items) and status-gating
 * (upload/delete only while the parent report is Draft/Policy Rejected/Query Raised) are
 * enforced inside the implementation — the caller is always taken from the security
 * context, never a request argument.
 */
public interface ReceiptService {

    ReceiptResponse upload(UUID lineItemId, MultipartFile file);

    List<ReceiptResponse> getAllForLineItem(UUID lineItemId);

    ReceiptResponse getById(UUID receiptId);

    /** Time-limited URL for inline browser preview. */
    ReceiptUrlResponse getViewUrl(UUID receiptId);

    /** Time-limited URL that forces a download with the receipt's original file name. */
    ReceiptUrlResponse getDownloadUrl(UUID receiptId);

    void delete(UUID receiptId);
}
