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

    /**
     * Same person resolving as approver at two different levels - the later occurrence is auto-skipped
     * (§2.6). A split-owner assignment (one with any {@code ApprovalSplitReview} children) can still be
     * skipped as a duplicate of the header/normal entry (or another split-owner entry) resolved at
     * THAT SAME LEVEL - e.g. the Cost Center Owner who is also the report's header Cost Center Owner,
     * the already-established "waived split" design ({@link
     * com.expense_management_service.service.impl.SplitApprovalBudgetIntegrationTest}'s {@code
     * duplicateApproverSkip_waivesASplitOwnersSplits_...} /
     * {@code selfApprovalAndDuplicateSkip_combineCorrectly_...}). What it must never do is treat an
     * EARLIER, DIFFERENT level's assignment for the same person as a reason to skip a split-owner
     * assignment at a LATER level (or vice versa) - Phase 4's design has each level's split-owner
     * assignments act in parallel, covering a disjoint slice of the report (their own splits) that is
     * independent of whatever that same person did at any other level. A Cost Center Owner reviewing
     * their own split at Level 2 is not a redundant repeat of the same person's Level 1 Reporting
     * Manager sign-off - it is a different kind of review over different content.
     * <p>
     * Bug fix (production-readiness follow-up): before this fix, {@code seen} was one set shared
     * across every level, so an employee who was BOTH the Level 1 Reporting Manager AND a Level 2 Cost
     * Center Owner had their Level 2 split-owner assignment silently auto-skipped here as a "duplicate"
     * of their (by-then-completed) Level 1 assignment - the {@code ApprovalSplitReview} stayed
     * genuinely PENDING (confirmed via {@code getSplitReviews}), but the assignment backing it flipped
     * to SKIPPED, so {@code matchingAssignments}' {@code status == ACTIVE} filter silently excluded it
     * from {@code getMyQueue}, even though nothing was wrong with the queue/filtering logic itself -
     * the assignment it was reading had already been incorrectly skipped upstream, at submission time,
     * by this method. Fix: track "seen this level" separately from "seen across earlier levels" - a
     * split-owner assignment is checked (and, if it's the duplicate, recorded) only against the
     * CURRENT level's set, and never contributes to the cross-level set a later level's NORMAL-track
     * assignment is checked against.
     * <p>
     * Second bug fix (same follow-up): a report whose header Cost Center happens to BE one of its own
     * split's Cost Centers can resolve a REDUNDANT normal-track entry at the split-owner's own level
     * (the entries loop always resolves the level's normal COST_CENTER_OWNER entry against {@code
     * report.getCostCenter()}, regardless of whether any unsplit remainder actually exists for it to
     * review). When that redundant normal-track entry is ITSELF a cross-level duplicate (e.g. the same
     * person already appeared as an earlier level's Reporting Manager) and gets skipped, its
     * approverId must NOT be recorded into {@code seenAtThisLevel} - otherwise the genuine split-owner
     * assignment processed right after it falsely matches against an entry that was itself already
     * eliminated, and gets skipped too, exactly reproducing this bug. Only a normal-track entry that
     * itself SURVIVES (is not a duplicate) may suppress a true same-level split-owner duplicate.
     */
    private void applyDuplicateApproverPass(java.util.List<ApprovalLevelInstance> instances) {
        Set<String> seenAcrossLevels = new HashSet<>();
        for (ApprovalLevelInstance instance : instances) {
            Set<String> seenAtThisLevel = new HashSet<>();
            for (ApprovalAssignment assignment : approvalAssignmentRepository.findByLevelInstance_InstanceId(instance.getInstanceId())) {
                if (assignment.getStatus() == AssignmentStatus.SKIPPED) {
                    continue;
                }
                boolean isSplitOwner = !assignment.getSplitReviews().isEmpty();
                boolean duplicate;
                if (isSplitOwner) {
                    duplicate = !seenAtThisLevel.add(assignment.getApproverId());
                } else {
                    duplicate = !seenAcrossLevels.add(assignment.getApproverId());
                    if (!duplicate) {
                        seenAtThisLevel.add(assignment.getApproverId()); // only a surviving normal-track entry can suppress a same-level split-owner duplicate
                    }
                }
                if (duplicate) {
                    assignment.setStatus(AssignmentStatus.SKIPPED);
                    approvalAssignmentRepository.save(assignment);
                    log.info("Auto-skipped assignment {} - approver {} already appears earlier in this chain",
                            assignment.getAssignmentId(), assignment.getApproverId());
                }
            }
        }
    }
}
