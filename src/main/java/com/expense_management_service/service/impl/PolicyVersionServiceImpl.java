package com.expense_management_service.service.impl;

import com.expense_management_service.dto.request.AuditLogRequest;
import com.expense_management_service.dto.response.PolicyVersionResponse;
import com.expense_management_service.entity.Policy;
import com.expense_management_service.entity.PolicyVersion;
import com.expense_management_service.repository.PolicyVersionRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.AuditLogService;
import com.expense_management_service.service.PolicyVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PolicyVersionServiceImpl implements PolicyVersionService {

    private static final String ENTITY_NAME = "Policy";
    private static final String ACTION = "VERSION_ACTIVATED";

    private final PolicyVersionRepository policyVersionRepository;
    private final AuditLogService auditLogService;
    private final CurrentUserService currentUserService;

    @Override
    public int activateNewVersion(Policy policy) {
        int newVersionNumber = getCurrentVersion(policy.getPolicyId()) + 1;
        PolicyVersion version = PolicyVersion.builder()
                .policy(policy)
                .versionNumber(newVersionNumber)
                .activatedAt(LocalDateTime.now())
                .build();
        policyVersionRepository.save(version);
        log.info("Activated policy {} version {}", policy.getPolicyId(), newVersionNumber);
        recordAudit(policy, newVersionNumber);
        return newVersionNumber;
    }

    @Override
    @Transactional(readOnly = true)
    public int getCurrentVersion(UUID policyId) {
        return policyVersionRepository.findTopByPolicy_PolicyIdOrderByVersionNumberDesc(policyId)
                .map(PolicyVersion::getVersionNumber)
                .orElse(1);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PolicyVersionResponse> getVersionHistory(UUID policyId) {
        return policyVersionRepository.findByPolicy_PolicyIdOrderByVersionNumberDesc(policyId).stream()
                .map(v -> new PolicyVersionResponse(v.getVersionId(), policyId, v.getVersionNumber(), v.getActivatedAt()))
                .toList();
    }

    /** Who changed a policy and what changed is the generic AuditLog's job - PolicyVersion itself only logs when a version number was activated. */
    private void recordAudit(Policy policy, int newVersionNumber) {
        String performedBy = currentUserService.getCurrentUser().employeeId();
        auditLogService.create(new AuditLogRequest(ENTITY_NAME, policy.getPolicyId(), ACTION,
                null, "version " + newVersionNumber, performedBy));
    }
}
