package com.expense_management_service.repository;

import com.expense_management_service.entity.PolicyAssignment;
import com.expense_management_service.enums.PolicyAssignmentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Query methods backing {@code PolicyAssignmentResolver}'s Individual &gt; Group &gt; Default precedence. */
public interface PolicyAssignmentRepository extends JpaRepository<PolicyAssignment, UUID> {

    Optional<PolicyAssignment> findFirstByEmployeeIdAndAssignmentTypeAndStatus(
            String employeeId, PolicyAssignmentType assignmentType, String status);

    Optional<PolicyAssignment> findFirstByGroup_GroupIdAndAssignmentTypeAndStatus(
            UUID groupId, PolicyAssignmentType assignmentType, String status);

    Optional<PolicyAssignment> findFirstByAssignmentTypeAndStatus(PolicyAssignmentType assignmentType, String status);

    /** Delete guard for a policy bundle - see {@code PolicyServiceImpl#delete}. Also what keeps the seeded Default Policy itself undeletable. */
    boolean existsByPolicy_PolicyId(UUID policyId);
}
