package com.expense_management_service.service.impl;

import java.util.List;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ApprovalDelegationRequest;
import com.expense_management_service.dto.response.ApprovalDelegationResponse;
import com.expense_management_service.entity.ApprovalDelegation;
import com.expense_management_service.enums.DelegationStatus;
import com.expense_management_service.mapper.ApprovalDelegationMapper;
import com.expense_management_service.repository.ApprovalDelegationRepository;
import com.expense_management_service.service.ApprovalDelegationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ApprovalDelegationServiceImpl implements ApprovalDelegationService {

    private final ApprovalDelegationRepository approvalDelegationRepository;
    private final ApprovalDelegationMapper approvalDelegationMapper;

    /**
     * Gates the "delegate must hold equal or greater approval authority than the delegator" rule.
     * Defaults to disabled: there is no role-ranking data source to enforce it against today -
     * {@code RoleConstants} defines role names only, with no hierarchy, and neither EmployeeCache
     * nor any UMS endpoint exposes an arbitrary employeeId's role. See {@link #assertAuthorityIfEnabled}.
     */
    @Value("${delegation.authority-check.enabled:false}")
    private boolean authorityCheckEnabled;

    @Override
    public ApprovalDelegationResponse create(ApprovalDelegationRequest request) {
        ApprovalDelegation entity = approvalDelegationMapper.toEntity(request);
        assertAuthorityIfEnabled(entity);
        warnOnOverlap(entity);
        return approvalDelegationMapper.toResponse(approvalDelegationRepository.save(entity));
    }

    @Override
    public ApprovalDelegationResponse update(UUID delegationId, ApprovalDelegationRequest request) {
        ApprovalDelegation entity = findEntity(delegationId);
        approvalDelegationMapper.updateEntity(entity, request);
        return approvalDelegationMapper.toResponse(approvalDelegationRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public ApprovalDelegationResponse getById(UUID delegationId) {
        return approvalDelegationMapper.toResponse(findEntity(delegationId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApprovalDelegationResponse> getAll() {
        return approvalDelegationRepository.findAll().stream().map(approvalDelegationMapper::toResponse).toList();
    }

    @Override
    public void delete(UUID delegationId) {
        approvalDelegationRepository.delete(findEntity(delegationId));
    }

    private ApprovalDelegation findEntity(UUID delegationId) {
        return approvalDelegationRepository.findById(delegationId)
                .orElseThrow(() -> new ResourceNotFoundException("ApprovalDelegation not found with id: " + delegationId));
    }

    /**
     * Deliberate no-op enforcement (logs only) when enabled - see the field Javadoc. Kept as a
     * real, callable flag rather than removed outright, so flipping it on is a one-line config
     * change once UMS's role-ranking contract is confirmed, instead of a code change.
     */
    private void assertAuthorityIfEnabled(ApprovalDelegation entity) {
        if (!authorityCheckEnabled) {
            return;
        }
        log.warn("delegation.authority-check.enabled is true, but no role-ranking data source exists yet "
                        + "(RoleConstants has no hierarchy; no UMS endpoint resolves an arbitrary employeeId's role) - "
                        + "skipping enforcement for delegation from {} to {}",
                entity.getDelegatorId(), entity.getDelegateId());
    }

    /**
     * Later-created delegation wins at lookup time (see DelegationService.canAct) - this only
     * surfaces the conflict so an admin notices, it never blocks creation.
     */
    private void warnOnOverlap(ApprovalDelegation entity) {
        if (entity.getDelegatorId() == null || entity.getStartDate() == null || entity.getEndDate() == null) {
            return;
        }
        approvalDelegationRepository.findByDelegatorIdAndStatusNot(entity.getDelegatorId(), DelegationStatus.CANCELLED).stream()
                .filter(other -> other.getStartDate() != null && other.getEndDate() != null)
                .filter(other -> !entity.getEndDate().isBefore(other.getStartDate())
                        && !other.getEndDate().isBefore(entity.getStartDate()))
                .forEach(other -> log.warn(
                        "New delegation for delegator {} ({} to {}) overlaps existing delegation {} ({} to {}); "
                                + "the later-created delegation takes precedence for the overlapping days",
                        entity.getDelegatorId(), entity.getStartDate(), entity.getEndDate(),
                        other.getDelegationId(), other.getStartDate(), other.getEndDate()));
    }
}
