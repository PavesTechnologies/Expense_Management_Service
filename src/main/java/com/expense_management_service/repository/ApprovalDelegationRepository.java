package com.expense_management_service.repository;

import com.expense_management_service.entity.ApprovalDelegation;
import com.expense_management_service.enums.DelegationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApprovalDelegationRepository extends JpaRepository<ApprovalDelegation, UUID> {

    /**
     * Every non-cancelled delegation for a delegator, regardless of date range - callers filter
     * for "currently in effect" in-memory (ACTIVE vs. SCHEDULED vs. EXPIRED is date-driven, not
     * a status this table reliably transitions on its own; see {@link DelegationStatus}).
     */
    List<ApprovalDelegation> findByDelegatorIdAndStatusNot(String delegatorId, DelegationStatus status);
}
