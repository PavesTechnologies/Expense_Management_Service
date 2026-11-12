package com.expense_management_service.repository;

import com.expense_management_service.entity.VerificationQuery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VerificationQueryRepository extends JpaRepository<VerificationQuery, UUID> {

    /** Finance Verification's own queries only - {@code levelInstance} is null for rows created via the generic {@code VerificationQueryController}. */
    List<VerificationQuery> findByLevelInstance_InstanceIdAndStatus(UUID instanceId, String status);
}
