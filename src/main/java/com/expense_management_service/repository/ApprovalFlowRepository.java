package com.expense_management_service.repository;

import com.expense_management_service.entity.ApprovalFlow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalFlowRepository extends JpaRepository<ApprovalFlow, UUID> {

    /** Every non-catch-all flow, in evaluation order. */
    List<ApprovalFlow> findByIsCatchAllFalseAndStatusOrderByPriorityAsc(String status);

    /** The single mandatory catch-all flow, if configured. */
    Optional<ApprovalFlow> findByIsCatchAllTrue();

    boolean existsByPriorityAndIsCatchAllFalse(Integer priority);
}
