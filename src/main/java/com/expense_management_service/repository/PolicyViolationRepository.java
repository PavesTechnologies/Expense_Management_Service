package com.expense_management_service.repository;

import com.expense_management_service.entity.PolicyViolation;
import com.expense_management_service.enums.PolicyEnforcementType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyViolationRepository extends JpaRepository<PolicyViolation, UUID> {

    List<PolicyViolation> findByLineItem_LineItemId(UUID lineItemId);

    List<PolicyViolation> findByLineItem_Report_ReportId(UUID reportId);

    /** Batched lookup for approver queue triage — avoids an N+1 query per task in {@code getMyQueue}. */
    List<PolicyViolation> findByLineItem_Report_ReportIdIn(Collection<UUID> reportIds);

    /** Path-scoped lookup — guarantees a violation is only ever addressed through its own parent line item. */
    Optional<PolicyViolation> findByViolationIdAndLineItem_LineItemId(UUID violationId, UUID lineItemId);

    /** The Block gate's fast path — a single lightweight existence check, not a fetch, since most reports have no BLOCK violations. */
    boolean existsByLineItem_Report_ReportIdAndEnforcementType(UUID reportId, PolicyEnforcementType enforcementType);

    /** Only called when the exists-check above is true, to build an itemized rejection message. */
    List<PolicyViolation> findByLineItem_Report_ReportIdAndEnforcementType(UUID reportId, PolicyEnforcementType enforcementType);
}
