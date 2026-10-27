package com.expense_management_service.repository;

import com.expense_management_service.entity.CashAdvanceCarryForward;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CashAdvanceCarryForwardRepository extends JpaRepository<CashAdvanceCarryForward, UUID> {

    /** Locks the allocation row first, so an approve and a reverse on the same allocation cannot both run. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CashAdvanceCarryForward c where c.carryForwardId = :id")
    Optional<CashAdvanceCarryForward> findByIdForUpdate(@Param("id") UUID id);

    Optional<CashAdvanceCarryForward> findByIdempotencyKey(String idempotencyKey);

    List<CashAdvanceCarryForward> findBySourceAdvance_AdvanceIdAndStatusIn(UUID sourceAdvanceId, Collection<String> statuses);

    List<CashAdvanceCarryForward> findByDestinationAdvance_AdvanceIdAndStatusIn(UUID destinationAdvanceId, Collection<String> statuses);

    List<CashAdvanceCarryForward> findByStatusIn(Collection<String> statuses);
}
