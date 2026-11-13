package com.expense_management_service.repository;

import com.expense_management_service.entity.CashAdvanceDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CashAdvanceDocumentRepository extends JpaRepository<CashAdvanceDocument, UUID> {

    /** Documents still attached to the request (removed ones are kept for history but not listed). */
    List<CashAdvanceDocument> findByAdvance_AdvanceIdAndDeletedAtIsNullOrderByUploadedAtAsc(UUID advanceId);

    /** How many documents are still attached. Used to enforce the per-request limit. */
    long countByAdvance_AdvanceIdAndDeletedAtIsNull(UUID advanceId);
}
