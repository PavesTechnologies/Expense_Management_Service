package com.expense_management_service.repository;

import com.expense_management_service.entity.SupplementaryExpenseClaim;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupplementaryExpenseClaimRepository extends JpaRepository<SupplementaryExpenseClaim, UUID> {

    /** Locks a claim row so a decision, submission or AP link cannot race with another change to the same claim. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SupplementaryExpenseClaim c where c.claimId = :id")
    Optional<SupplementaryExpenseClaim> findByIdForUpdate(@Param("id") UUID id);

    List<SupplementaryExpenseClaim> findByOriginal_ReportIdAndStatusIn(UUID reportId, Collection<String> statuses);

    List<SupplementaryExpenseClaim> findByEmployeeIdOrderByCreatedAtDesc(String employeeId);

    /** Claims already set (in part) against this advance's carried-forward balance. */
    List<SupplementaryExpenseClaim> findBySettledAdvanceId(UUID settledAdvanceId);

    List<SupplementaryExpenseClaim> findByStatusInOrderBySubmittedAtAsc(Collection<String> statuses);

    boolean existsByOriginal_ReportIdAndStatusIn(UUID reportId, Collection<String> statuses);
}
