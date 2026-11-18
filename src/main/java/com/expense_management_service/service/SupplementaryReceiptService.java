package com.expense_management_service.service;

import com.expense_management_service.dto.response.SupplementaryReceiptResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Receipts for supplementary claim items. A receipt belongs to one item at a time, and a file can be used on one claim only.
 * An original report receipt needs a Finance release before it can be attached.
 */
public interface SupplementaryReceiptService {

    /** Uploads a new receipt and attaches it to the item. */
    void upload(UUID claimId, UUID itemId, MultipartFile file);

    /**
     * Stores a receipt before its expense exists, so OCR can read it. The receipt is not attached to any expense until the
     * employee confirms the expense from it (see SupplementaryClaimService.addItemFromReceipt).
     */
    java.util.UUID uploadScan(UUID claimId, MultipartFile file);

    /** Attaches an existing receipt: an upload that is not attached yet, or an original receipt Finance has released. */
    void attach(UUID claimId, UUID itemId, UUID receiptId);

    /** Detaches a receipt from an item while the claim can still be edited. */
    void detach(UUID claimId, UUID itemId, UUID receiptId);

    /** Original report receipts that Finance released and that have not been used yet. */
    List<SupplementaryReceiptResponse> releasableForCurrentEmployee();

    /** What OCR read from one of the caller's receipts, to fill an expense automatically. */
    com.expense_management_service.dto.response.SupplementaryReceiptSuggestionResponse suggestionFor(UUID receiptId);

    /** Detaches every receipt from a claim. Used when the claim is cancelled or rejected; released receipts stay used. */
    void detachAllForClaim(UUID claimId);

    /**
     * Deletes the receipts of an expense that is being removed: the stored files and their rows, the same way a report
     * receipt is deleted. Called before the expense itself goes.
     */
    void deleteAllForItem(UUID itemId);
}
