package com.expense_management_service.service;

import com.expense_management_service.dto.request.SupplementaryClaimCreateRequest;
import com.expense_management_service.dto.request.SupplementaryClaimItemRequest;
import com.expense_management_service.dto.response.SupplementaryClaimResponse;
import com.expense_management_service.dto.response.SupplementaryEligibilityResponse;

import java.util.List;
import java.util.UUID;

/**
 * Supplementary expense claims: missing expenses on a report that is already settled, submitted as a separate claim.
 * The original report and its settlement are never modified.
 */
public interface SupplementaryClaimService {

    /** Outcome of a manager or Finance review. */
    enum Decision { APPROVE, REJECT, CLARIFY }

    SupplementaryEligibilityResponse checkEligibility(UUID reportId);

    /** Employee creates a claim for their own settled report. */
    SupplementaryClaimResponse createForSelf(SupplementaryClaimCreateRequest request);

    /** Finance creates a claim for an employee. The employee must acknowledge it before it can be submitted. */
    SupplementaryClaimResponse createOnBehalf(SupplementaryClaimCreateRequest request);

    SupplementaryClaimResponse get(UUID claimId);

    List<SupplementaryClaimResponse> listMine();

    /** Every supplementary claim raised against one of the caller's own reports. */
    List<SupplementaryClaimResponse> listForReport(UUID reportId);

    SupplementaryClaimResponse addItem(UUID claimId, SupplementaryClaimItemRequest request);

    /**
     * Creates an expense from a receipt uploaded for this claim (the automatic path). The details come from the employee,
     * who confirms the values OCR suggested; the receipt is then attached to the new expense.
     */
    SupplementaryClaimResponse addItemFromReceipt(UUID claimId, UUID receiptId, SupplementaryClaimItemRequest request);

    SupplementaryClaimResponse updateItem(UUID claimId, UUID itemId, SupplementaryClaimItemRequest request);

    SupplementaryClaimResponse removeItem(UUID claimId, UUID itemId);

    /** The employee acknowledges a claim that Finance created for them. This is not an approval. */
    SupplementaryClaimResponse acknowledge(UUID claimId);

    SupplementaryClaimResponse submit(UUID claimId);

    /** Employee resubmits a claim that was sent back for clarification, returning it to the stage that asked. */
    SupplementaryClaimResponse resubmit(UUID claimId);

    SupplementaryClaimResponse cancel(UUID claimId);

    List<SupplementaryClaimResponse> managerQueue();

    SupplementaryClaimResponse managerReview(UUID claimId, Decision decision, String comment);

    List<SupplementaryClaimResponse> financeQueue();

    SupplementaryClaimResponse financeReview(UUID claimId, Decision decision, String comment);
}
