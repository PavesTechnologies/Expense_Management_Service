package com.expense_management_service.repository;

import com.expense_management_service.entity.ApprovalSplitReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalSplitReviewRepository extends JpaRepository<ApprovalSplitReview, UUID> {

    List<ApprovalSplitReview> findByAssignment_AssignmentId(UUID assignmentId);

    Optional<ApprovalSplitReview> findBySplit_SplitIdAndAssignment_AssignmentId(UUID splitId, UUID assignmentId);
}
