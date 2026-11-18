package com.expense_management_service.repository;

import com.expense_management_service.entity.Receipt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReceiptRepository extends JpaRepository<Receipt, UUID> {

    /** Locks a receipt row so two claims cannot attach the same receipt at the same moment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Receipt r where r.receiptId = :id")
    Optional<Receipt> findByIdForUpdate(@Param("id") UUID id);

    /** Every receipt on a report — including ones not yet linked to a line item. */
    List<Receipt> findByReport_ReportId(UUID reportId);

    /** Retained for the (still-supported) line-item-scoped listing endpoint. */
    List<Receipt> findByLineItem_LineItemId(UUID lineItemId);

    /** Every receipt sharing this exact file hash, across every employee/report — advisory-only exact-file-reuse detection, see {@code ReceiptServiceImpl}. */
    List<Receipt> findByFileHash(String fileHash);

    /** Late receipts waiting for Finance/AP review, oldest first. */
    List<Receipt> findByAttachmentKindAndVerificationStatusOrderByUploadedAtAsc(String attachmentKind, String verificationStatus);

    /** Late receipts in any of these review states, newest first (the reviewed history on the Finance page). */
    List<Receipt> findByAttachmentKindAndVerificationStatusInOrderByUploadedAtDesc(String attachmentKind,
                                                                                  java.util.Collection<String> verificationStatuses);

    /** Receipts attached to one supplementary claim item. */
    List<Receipt> findBySupplementaryClaimItem_ClaimItemId(UUID claimItemId);

    /** Receipts attached to any item of one supplementary claim. Used to detach them when the claim is cancelled or rejected. */
    List<Receipt> findBySupplementaryClaimItem_Claim_ClaimId(UUID claimId);

    /** True when this exact file is already attached to a supplementary claim item, so it cannot be used twice. */
    boolean existsByFileHashAndSupplementaryClaimItemIsNotNull(String fileHash);

    /** An employee's receipts that are not supplementary uploads: candidates for a Finance release. */
    List<Receipt> findByEmployeeIdAndAttachmentKindNot(String employeeId, String attachmentKind);
}
