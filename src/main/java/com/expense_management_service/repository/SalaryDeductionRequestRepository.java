package com.expense_management_service.repository;

import com.expense_management_service.entity.SalaryDeductionRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SalaryDeductionRequestRepository extends JpaRepository<SalaryDeductionRequest, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SalaryDeductionRequest r where r.salaryDeductionRequestId = :id")
    Optional<SalaryDeductionRequest> findByIdForUpdate(@Param("id") UUID id);

    List<SalaryDeductionRequest> findByAdvance_AdvanceIdOrderByRequestedAtDesc(UUID advanceId);

    List<SalaryDeductionRequest> findByAdvance_AdvanceIdAndStatusIn(UUID advanceId, Collection<String> statuses);
}
