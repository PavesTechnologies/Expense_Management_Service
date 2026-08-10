package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ApprovalAssignment;
import com.expense_management_service.entity.ApprovalLevelInstance;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.SystemConfiguration;
import com.expense_management_service.enums.AssignmentStatus;
import com.expense_management_service.repository.ApprovalAssignmentRepository;
import com.expense_management_service.repository.ApprovalLevelInstanceRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.repository.SystemConfigurationRepository;
import com.expense_management_service.service.ChainCorrectnessService;
import com.expense_management_service.service.DelegationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ChainCorrectnessServiceImpl implements ChainCorrectnessService {

    /** Same SystemConfiguration key EP06 used - the ultimate backstop when the self-approval cascade exhausts delegate and manager. */
    static final String DEFAULT_APPROVER_CONFIG_KEY = "approval.default-approver-employee-id";

    private final ApprovalLevelInstanceRepository approvalLevelInstanceRepository;
    private final ApprovalAssignmentRepository approvalAssignmentRepository;
    private final EmployeeCacheRepository employeeCacheRepository;
    private final SystemConfigurationRepository systemConfigurationRepository;
    private final DelegationService delegationService;

    @Override
    public void applyCorrectnessPasses(ExpenseReport report, int submissionCycle) {
        var instances = approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(report.getReportId(), submissionCycle);

        applySelfApprovalPass(report, instances);
        applyDuplicateApproverPass(instances);
    }

    /**
     * Delegate -> reporting manager -> Default Approver (§5.1). Deliberately more automatic than
     * SLA escalation (§5.4, reminders-only): a self-approval conflict is a hard rule violation that
     * must never be allowed to stand, never waits on Admin.
     */
    private void applySelfApprovalPass(ExpenseReport report, java.util.List<ApprovalLevelInstance> instances) {
        String submitterId = report.getEmployeeId();
        for (ApprovalLevelInstance instance : instances) {
            for (ApprovalAssignment assignment : approvalAssignmentRepository.findByLevelInstance_InstanceId(instance.getInstanceId())) {
                if (!submitterId.equals(assignment.getApproverId())) {
                    continue;
                }
                String replacement = resolveSelfApprovalReplacement(submitterId);
                log.info("Self-approval detected for report {} (submitter/approver {}) at level {} - redirecting to {}",
                        report.getReportId(), submitterId, instance.getLevelOrder(), replacement);
                assignment.setSupersededApproverId(assignment.getApproverId());
                assignment.setApproverId(replacement);
                approvalAssignmentRepository.save(assignment);
            }
        }
    }

    private String resolveSelfApprovalReplacement(String submitterId) {
        return delegationService.resolveActiveDelegate(submitterId)
                .or(() -> employeeCacheRepository.findByEmployeeId(submitterId)
                        .map(EmployeeCache::getManagerEmployeeId)
                        .filter(managerId -> managerId != null && !managerId.isBlank() && !managerId.equals(submitterId)))
                .or(this::resolveDefaultApprover)
                .orElseThrow(() -> new IllegalStateException(
                        "Self-approval detected for " + submitterId + " but no delegate, manager, or Default Approver "
                                + "(SystemConfiguration key '" + DEFAULT_APPROVER_CONFIG_KEY + "') is configured"));
    }

    private java.util.Optional<String> resolveDefaultApprover() {
        return systemConfigurationRepository.findByConfigKey(DEFAULT_APPROVER_CONFIG_KEY)
                .map(SystemConfiguration::getConfigValue)
                .filter(value -> value != null && !value.isBlank());
    }

    /** Same person resolving as approver at two different levels - the later occurrence is auto-skipped (§2.6). */
    private void applyDuplicateApproverPass(java.util.List<ApprovalLevelInstance> instances) {
        Set<String> seen = new HashSet<>();
        for (ApprovalLevelInstance instance : instances) {
            for (ApprovalAssignment assignment : approvalAssignmentRepository.findByLevelInstance_InstanceId(instance.getInstanceId())) {
                if (assignment.getStatus() == AssignmentStatus.SKIPPED) {
                    continue;
                }
                if (!seen.add(assignment.getApproverId())) {
                    assignment.setStatus(AssignmentStatus.SKIPPED);
                    approvalAssignmentRepository.save(assignment);
                    log.info("Auto-skipped assignment {} - approver {} already appears earlier in this chain",
                            assignment.getAssignmentId(), assignment.getApproverId());
                }
            }
        }
    }
}
