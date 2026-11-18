package com.expense_management_service.service;

import com.expense_management_service.dto.request.ReceiptReleaseDecisionRequest;
import com.expense_management_service.dto.request.ReceiptReleaseRequest;
import com.expense_management_service.dto.response.ReceiptReleaseResponse;
import com.expense_management_service.dto.response.SupplementaryReceiptResponse;

import java.util.List;
import java.util.UUID;

/**
 * Two-person release of a Finance-rejected receipt. One Finance user requests it with a reason; a different Finance user
 * approves or rejects. Every request stays in the history. A released receipt can attach to one claim, and once used it
 * cannot be released again.
 */
public interface ReceiptReleaseService {

    ReceiptReleaseResponse request(ReceiptReleaseRequest request);

    ReceiptReleaseResponse approve(UUID releaseId, ReceiptReleaseDecisionRequest decision);

    ReceiptReleaseResponse reject(UUID releaseId, ReceiptReleaseDecisionRequest decision);

    /** Requests waiting for a second user's decision, oldest first. */
    List<ReceiptReleaseResponse> queue();

    /** Every release request for one receipt, newest first. */
    List<ReceiptReleaseResponse> historyFor(UUID receiptId);

    /** An employee's rejected receipts that can still be released, for Finance to pick from. */
    List<SupplementaryReceiptResponse> candidatesFor(String employeeId);
}
