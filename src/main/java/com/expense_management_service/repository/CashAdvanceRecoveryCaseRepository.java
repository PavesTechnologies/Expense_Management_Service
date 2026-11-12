package com.expense_management_service.repository;

import com.expense_management_service.entity.CashAdvanceRecoveryCase;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CashAdvanceRecoveryCaseRepository extends JpaRepository<CashAdvanceRecoveryCase, UUID> {

    /** The open case for an advance, if any: a notice is out and the case has not been returned yet. */
    Optional<CashAdvanceRecoveryCase> findFirstByAdvance_AdvanceIdAndStatusIn(UUID advanceId, Collection<String> statuses);

    List<CashAdvanceRecoveryCase> findByStatusAndDueAtLessThanEqual(String status, LocalDateTime now);

    List<CashAdvanceRecoveryCase> findByStatusIn(Collection<String> statuses);

    List<CashAdvanceRecoveryCase> findByAdvance_AdvanceIdAndStatusIn(UUID advanceId, Collection<String> statuses);

    /** Locks the case so a repeated escalation run cannot send payroll report twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CashAdvanceRecoveryCase c where c.recoveryCaseId = :id")
    Optional<CashAdvanceRecoveryCase> findByIdForUpdate(@Param("id") UUID id);
}
