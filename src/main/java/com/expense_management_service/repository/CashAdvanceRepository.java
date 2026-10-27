package com.expense_management_service.repository;

import com.expense_management_service.entity.CashAdvance;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CashAdvanceRepository extends JpaRepository<CashAdvance, UUID> {

    /** Loads the advance with a row lock, so two balance changes on the same advance cannot interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CashAdvance a where a.advanceId = :advanceId")
    Optional<CashAdvance> findByIdForUpdate(@Param("advanceId") UUID advanceId);

    List<CashAdvance> findByEmployeeId(String employeeId);
    List<CashAdvance> findByEmployeeIdAndStatus(String employeeId, String status);
    List<CashAdvance> findByEmployeeIdAndStatusIn(String employeeId, Collection<String> statuses);
    List<CashAdvance> findByStatus(String status);
    List<CashAdvance> findByStatusIn(Collection<String> statuses);
    List<CashAdvance> findByManagerId(String managerId);
    List<CashAdvance> findByManagerIdAndStatus(String managerId, String status);
    List<CashAdvance> findByManagerIdIn(Collection<String> managerIds);
    List<CashAdvance> findByManagerIdInAndStatus(Collection<String> managerIds, String status);
    List<CashAdvance> findByManagerIdInAndStatusIn(Collection<String> managerIds, Collection<String> statuses);

    Page<CashAdvance> findByEmployeeId(String employeeId, Pageable pageable);
    Page<CashAdvance> findByEmployeeIdAndStatus(String employeeId, String status, Pageable pageable);
    Page<CashAdvance> findByEmployeeIdAndStatusIn(String employeeId, Collection<String> statuses, Pageable pageable);
    Page<CashAdvance> findByStatus(String status, Pageable pageable);
    Page<CashAdvance> findByStatusIn(Collection<String> statuses, Pageable pageable);
    Page<CashAdvance> findByManagerId(String managerId, Pageable pageable);
    Page<CashAdvance> findByManagerIdAndStatus(String managerId, String status, Pageable pageable);
    Page<CashAdvance> findByManagerIdIn(Collection<String> managerIds, Pageable pageable);
    Page<CashAdvance> findByManagerIdInAndStatus(Collection<String> managerIds, String status, Pageable pageable);
    Page<CashAdvance> findByManagerIdInAndStatusIn(Collection<String> managerIds, Collection<String> statuses, Pageable pageable);
}
