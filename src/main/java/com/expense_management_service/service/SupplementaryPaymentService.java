package com.expense_management_service.service;

import com.expense_management_service.dto.request.SupplementaryPaymentConfirmRequest;
import com.expense_management_service.dto.response.SupplementaryPaymentResponse;
import com.expense_management_service.entity.SupplementaryExpenseClaim;

import java.util.List;
import java.util.UUID;

/**
 * AP payment for supplementary claims. A separate payment request is created when Finance approves a claim. The claim
 * becomes SETTLED only when AP confirms a payment on that request. The original expense report is never touched.
 */
public interface SupplementaryPaymentService {

    /** Creates the one payment request for a claim. Called in the same transaction as Finance approval. */
    void requestPayment(SupplementaryExpenseClaim claim);

    /** Requests an AP payment of {@code amount}: the part of the claim not set against a cash advance. */
    void requestPayment(SupplementaryExpenseClaim claim, java.math.BigDecimal amount);

    /** Requests waiting for AP, oldest first. */
    List<SupplementaryPaymentResponse> queue();

    /** Payments AP has confirmed, newest first. */
    List<SupplementaryPaymentResponse> paid();

    /** Confirms a payment. Marks the request PAID and the claim SETTLED, once and only once. */
    SupplementaryPaymentResponse confirm(UUID paymentId, SupplementaryPaymentConfirmRequest request);

    /** NONE, REQUESTED or PAID for a claim. */
    String paymentStatusOf(UUID claimId);

    /** The bank reference recorded for a claim, or null before it is paid. */
    String paymentReferenceOf(UUID claimId);
}
