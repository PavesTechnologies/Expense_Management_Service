package com.expense_management_service.repository;

import com.expense_management_service.entity.ReceiptRelease;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReceiptReleaseRepository extends JpaRepository<ReceiptRelease, UUID> {

    /** Locks a release request so only one Finance user can decide it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReceiptRelease r where r.releaseId = :id")
    Optional<ReceiptRelease> findByIdForUpdate(@Param("id") UUID id);

    /** Full release history of one receipt, newest first. Rejected requests stay here. */
    List<ReceiptRelease> findByReceiptIdOrderByRequestedAtDesc(UUID receiptId);

    /** True while a request for this receipt is still waiting for a decision. */
    boolean existsByReceiptIdAndStatusIn(UUID receiptId, Collection<String> statuses);

    /** The most recent release decision for a receipt. Only a RELEASED row can allow an attach. */
    Optional<ReceiptRelease> findFirstByReceiptIdOrderByDecidedAtDesc(UUID receiptId);

    /** The Finance queue: requests waiting for a second user's decision, oldest first. */
    List<ReceiptRelease> findByStatusOrderByRequestedAtAsc(String status);
}
