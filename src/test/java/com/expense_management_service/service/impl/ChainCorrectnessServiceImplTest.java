package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ApprovalAssignment;
import com.expense_management_service.entity.ApprovalLevelInstance;
import com.expense_management_service.entity.ApprovalSplitReview;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.SystemConfiguration;
import com.expense_management_service.enums.AssignmentStatus;
import com.expense_management_service.enums.LevelType;
import com.expense_management_service.enums.LineItemReviewStatus;
import com.expense_management_service.repository.ApprovalAssignmentRepository;
import com.expense_management_service.repository.ApprovalLevelInstanceRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.repository.SystemConfigurationRepository;
import com.expense_management_service.service.DelegationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// LENIENT: the shared save(any()) stub in setUp() is unused by the "cascade fully exhausted" test,
// which throws before ever reaching a save call.
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class ChainCorrectnessServiceImplTest {

    @Mock private ApprovalLevelInstanceRepository approvalLevelInstanceRepository;
    @Mock private ApprovalAssignmentRepository approvalAssignmentRepository;
    @Mock private EmployeeCacheRepository employeeCacheRepository;
    @Mock private SystemConfigurationRepository systemConfigurationRepository;
    @Mock private DelegationService delegationService;

    private ChainCorrectnessServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ChainCorrectnessServiceImpl(approvalLevelInstanceRepository, approvalAssignmentRepository,
                employeeCacheRepository, systemConfigurationRepository, delegationService);
        when(approvalAssignmentRepository.save(any(ApprovalAssignment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ExpenseReport reportBy(String submitterId) {
        return ExpenseReport.builder().reportId(UUID.randomUUID()).employeeId(submitterId).build();
    }

    private ApprovalLevelInstance instanceWithAssignment(ApprovalAssignment assignment) {
        ApprovalLevelInstance instance = ApprovalLevelInstance.builder().instanceId(UUID.randomUUID()).levelOrder(1).build();
        when(approvalAssignmentRepository.findByLevelInstance_InstanceId(instance.getInstanceId())).thenReturn(List.of(assignment));
        return instance;
    }

    @Test
    void applyCorrectnessPasses_redirectsToActiveDelegate_whenSelfApproval() {
        ExpenseReport report = reportBy("5100001");
        ApprovalAssignment selfAssignment = ApprovalAssignment.builder().assignmentId(UUID.randomUUID()).approverId("5100001").status(AssignmentStatus.PENDING).build();
        ApprovalLevelInstance instance = instanceWithAssignment(selfAssignment);
        when(approvalLevelInstanceRepository.findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(report.getReportId(), 1))
                .thenReturn(List.of(instance));
        when(delegationService.resolveActiveDelegate("5100001")).thenReturn(Optional.of("5100099"));

        service.applyCorrectnessPasses(report, 1);

        assertThat(selfAssignment.getApproverId()).isEqualTo("5100099");
        assertThat(selfAssignment.getSupersededApproverId()).isEqualTo("5100001");
    }

    @Test
    void applyCorrectnessPasses_redirectsToManager_whenNoDelegateAndManagerDiffersFromSubmitter() {
        ExpenseReport report = reportBy("5100001");
        ApprovalAssignment selfAssignment = ApprovalAssignment.builder().assignmentId(UUID.randomUUID()).approverId("5100001").status(AssignmentStatus.PENDING).build();
        ApprovalLevelInstance instance = instanceWithAssignment(selfAssignment);
        when(approvalLevelInstanceRepository.findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(report.getReportId(), 1))
                .thenReturn(List.of(instance));
        when(delegationService.resolveActiveDelegate("5100001")).thenReturn(Optional.empty());
        when(employeeCacheRepository.findByEmployeeId("5100001")).thenReturn(Optional.of(
                EmployeeCache.builder().employeeId("5100001").managerEmployeeId("5100002").build()));

        service.applyCorrectnessPasses(report, 1);

        assertThat(selfAssignment.getApproverId()).isEqualTo("5100002");
    }

    @Test
    void applyCorrectnessPasses_fallsBackToDefaultApprover_whenNoDelegateAndNoManager() {
        ExpenseReport report = reportBy("5100001");
        ApprovalAssignment selfAssignment = ApprovalAssignment.builder().assignmentId(UUID.randomUUID()).approverId("5100001").status(AssignmentStatus.PENDING).build();
        ApprovalLevelInstance instance = instanceWithAssignment(selfAssignment);
        when(approvalLevelInstanceRepository.findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(report.getReportId(), 1))
                .thenReturn(List.of(instance));
        when(delegationService.resolveActiveDelegate("5100001")).thenReturn(Optional.empty());
        when(employeeCacheRepository.findByEmployeeId("5100001")).thenReturn(Optional.empty());
        when(systemConfigurationRepository.findByConfigKey("approval.default-approver-employee-id")).thenReturn(Optional.of(
                SystemConfiguration.builder().configKey("approval.default-approver-employee-id").configValue("5100999").build()));

        service.applyCorrectnessPasses(report, 1);

        assertThat(selfAssignment.getApproverId()).isEqualTo("5100999");
    }

    @Test
    void applyCorrectnessPasses_throws_whenSelfApprovalCascadeFullyExhausted() {
        ExpenseReport report = reportBy("5100001");
        ApprovalAssignment selfAssignment = ApprovalAssignment.builder().assignmentId(UUID.randomUUID()).approverId("5100001").status(AssignmentStatus.PENDING).build();
        ApprovalLevelInstance instance = instanceWithAssignment(selfAssignment);
        when(approvalLevelInstanceRepository.findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(report.getReportId(), 1))
                .thenReturn(List.of(instance));
        when(delegationService.resolveActiveDelegate("5100001")).thenReturn(Optional.empty());
        when(employeeCacheRepository.findByEmployeeId("5100001")).thenReturn(Optional.empty());
        when(systemConfigurationRepository.findByConfigKey("approval.default-approver-employee-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.applyCorrectnessPasses(report, 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void applyCorrectnessPasses_skipsDuplicateApprover_appearingInALaterLevel() {
        ExpenseReport report = reportBy("5100001");
        ApprovalAssignment firstLevelAssignment = ApprovalAssignment.builder().assignmentId(UUID.randomUUID()).approverId("5100002").status(AssignmentStatus.PENDING).build();
        ApprovalAssignment secondLevelAssignment = ApprovalAssignment.builder().assignmentId(UUID.randomUUID()).approverId("5100002").status(AssignmentStatus.PENDING).build();

        ApprovalLevelInstance level1 = ApprovalLevelInstance.builder().instanceId(UUID.randomUUID()).levelOrder(1).build();
        ApprovalLevelInstance level2 = ApprovalLevelInstance.builder().instanceId(UUID.randomUUID()).levelOrder(2).build();
        when(approvalAssignmentRepository.findByLevelInstance_InstanceId(level1.getInstanceId())).thenReturn(List.of(firstLevelAssignment));
        when(approvalAssignmentRepository.findByLevelInstance_InstanceId(level2.getInstanceId())).thenReturn(List.of(secondLevelAssignment));
        when(approvalLevelInstanceRepository.findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(report.getReportId(), 1))
                .thenReturn(List.of(level1, level2));

        service.applyCorrectnessPasses(report, 1);

        assertThat(firstLevelAssignment.getStatus()).isEqualTo(AssignmentStatus.PENDING);
        assertThat(secondLevelAssignment.getStatus()).isEqualTo(AssignmentStatus.SKIPPED);
    }

    /**
     * Finance Verification regression (spec §14): neither pass filters by levelType - they already
     * walk "every assignment across every instance" regardless of type, so a person resolved as
     * both the Manager approver and the Finance approver (e.g. Finance is also Cost Center Owner)
     * is caught by the exact same duplicate-approver pass, with no Finance-specific code path.
     */
    @Test
    void applyCorrectnessPasses_skipsDuplicateApprover_whenSameEmployeeIsBothManagerAndFinanceApprover() {
        ExpenseReport report = reportBy("5100001");
        ApprovalAssignment managerAssignment = ApprovalAssignment.builder().assignmentId(UUID.randomUUID()).approverId("5100002").status(AssignmentStatus.PENDING).build();
        ApprovalAssignment financeAssignment = ApprovalAssignment.builder().assignmentId(UUID.randomUUID()).approverId("5100002").status(AssignmentStatus.PENDING).build();

        ApprovalLevelInstance managerLevel = ApprovalLevelInstance.builder().instanceId(UUID.randomUUID()).levelOrder(1).levelType(LevelType.APPROVAL).build();
        ApprovalLevelInstance financeLevel = ApprovalLevelInstance.builder().instanceId(UUID.randomUUID()).levelOrder(2).levelType(LevelType.FINANCE_VERIFICATION).build();
        when(approvalAssignmentRepository.findByLevelInstance_InstanceId(managerLevel.getInstanceId())).thenReturn(List.of(managerAssignment));
        when(approvalAssignmentRepository.findByLevelInstance_InstanceId(financeLevel.getInstanceId())).thenReturn(List.of(financeAssignment));
        when(approvalLevelInstanceRepository.findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(report.getReportId(), 1))
                .thenReturn(List.of(managerLevel, financeLevel));

        service.applyCorrectnessPasses(report, 1);

        assertThat(managerAssignment.getStatus()).isEqualTo(AssignmentStatus.PENDING);
        assertThat(financeAssignment.getStatus()).isEqualTo(AssignmentStatus.SKIPPED);
    }

    /**
     * Regression test (bug report: an employee who already approved at Level 1 as REPORTING_MANAGER
     * could not see/act on their pending Level 2 COST_CENTER_OWNER split review). Root cause: {@code
     * applyDuplicateApproverPass} treated the Level 2 split-owner assignment as a "duplicate" of the
     * Level 1 normal-track assignment for the same employee and auto-skipped it - even though the
     * split review itself stayed genuinely PENDING, the SKIPPED assignment made {@code
     * matchingAssignments}' {@code status == ACTIVE} filter silently exclude it from {@code
     * getMyQueue}. A split-owner assignment (one with split reviews) must never be skipped by this
     * pass, and must never count as an "earlier appearance" either.
     */
    @Test
    void applyCorrectnessPasses_neverSkipsASplitOwnerAssignment_whenSameEmployeeAlreadyApprovedAnEarlierNormalTrackLevel() {
        ExpenseReport report = reportBy("5100001");
        ApprovalAssignment reportingManagerAssignment = ApprovalAssignment.builder()
                .assignmentId(UUID.randomUUID()).approverId("5100002").status(AssignmentStatus.PENDING).build();
        ApprovalSplitReview splitReview = ApprovalSplitReview.builder()
                .reviewId(UUID.randomUUID()).status(LineItemReviewStatus.PENDING).build();
        ApprovalAssignment costCenterOwnerSplitAssignment = ApprovalAssignment.builder()
                .assignmentId(UUID.randomUUID()).approverId("5100002").status(AssignmentStatus.PENDING)
                .splitReviews(new java.util.ArrayList<>(List.of(splitReview))).build();

        ApprovalLevelInstance level1 = ApprovalLevelInstance.builder().instanceId(UUID.randomUUID()).levelOrder(1).build();
        ApprovalLevelInstance level2 = ApprovalLevelInstance.builder().instanceId(UUID.randomUUID()).levelOrder(2).build();
        when(approvalAssignmentRepository.findByLevelInstance_InstanceId(level1.getInstanceId())).thenReturn(List.of(reportingManagerAssignment));
        when(approvalAssignmentRepository.findByLevelInstance_InstanceId(level2.getInstanceId())).thenReturn(List.of(costCenterOwnerSplitAssignment));
        when(approvalLevelInstanceRepository.findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(report.getReportId(), 1))
                .thenReturn(List.of(level1, level2));

        service.applyCorrectnessPasses(report, 1);

        assertThat(reportingManagerAssignment.getStatus()).isEqualTo(AssignmentStatus.PENDING);
        assertThat(costCenterOwnerSplitAssignment.getStatus())
                .as("A split-owner assignment must never be auto-skipped as a 'duplicate' of an earlier normal-track assignment for the same employee")
                .isEqualTo(AssignmentStatus.PENDING);
    }

    /**
     * Regression test for the exact real-database follow-up: the report's header Cost Center happens
     * to BE the same Cost Center as one of its own splits, so Level 2 resolves THREE assignments - a
     * redundant normal-track COST_CENTER_OWNER entry for the header (no split reviews, since the line
     * item is fully split and there is no unsplit remainder for it to review), a genuine split-owner
     * assignment for that SAME person's own split, and a genuine split-owner assignment for a
     * different owner's split. The redundant header entry IS a real cross-level duplicate of Level 1's
     * Reporting Manager assignment for the same person and correctly gets skipped - but that skip must
     * NOT cascade into also skipping the genuine, still-pending split-owner assignment for the same
     * person at the same level, which was the residual bug in the first fix attempt (it recorded the
     * header entry into "seen at this level" before checking whether the header entry itself survived).
     */
    @Test
    void applyCorrectnessPasses_stillProtectsAGenuineSplitOwnerAssignment_whenARedundantSameLevelNormalTrackDuplicateIsSkipped() {
        ExpenseReport report = reportBy("5100001");
        String repeatedApprover = "5100023"; // Reporting Manager at Level 1, also Engineering's owner
        String otherOwner = "5100022"; // HR's owner - a genuinely distinct person

        ApprovalAssignment reportingManagerAssignment = ApprovalAssignment.builder()
                .assignmentId(UUID.randomUUID()).approverId(repeatedApprover).status(AssignmentStatus.PENDING).build();

        // Level 2's redundant normal-track entry - resolves to the report's header Cost Center owner,
        // who happens to be the same person as Level 1's Reporting Manager. No split reviews: the
        // line item is fully split, so there is genuinely nothing "unsplit" left for this to review.
        ApprovalAssignment redundantHeaderAssignment = ApprovalAssignment.builder()
                .assignmentId(UUID.randomUUID()).approverId(repeatedApprover).entryOrder(1).status(AssignmentStatus.PENDING).build();

        ApprovalSplitReview engineeringReview = ApprovalSplitReview.builder()
                .reviewId(UUID.randomUUID()).status(LineItemReviewStatus.PENDING).build();
        ApprovalAssignment engineeringSplitAssignment = ApprovalAssignment.builder()
                .assignmentId(UUID.randomUUID()).approverId(repeatedApprover).status(AssignmentStatus.PENDING)
                .splitReviews(new java.util.ArrayList<>(List.of(engineeringReview))).build();

        ApprovalSplitReview hrReview = ApprovalSplitReview.builder()
                .reviewId(UUID.randomUUID()).status(LineItemReviewStatus.PENDING).build();
        ApprovalAssignment hrSplitAssignment = ApprovalAssignment.builder()
                .assignmentId(UUID.randomUUID()).approverId(otherOwner).status(AssignmentStatus.PENDING)
                .splitReviews(new java.util.ArrayList<>(List.of(hrReview))).build();

        ApprovalLevelInstance level1 = ApprovalLevelInstance.builder().instanceId(UUID.randomUUID()).levelOrder(1).build();
        ApprovalLevelInstance level2 = ApprovalLevelInstance.builder().instanceId(UUID.randomUUID()).levelOrder(2).build();
        when(approvalAssignmentRepository.findByLevelInstance_InstanceId(level1.getInstanceId())).thenReturn(List.of(reportingManagerAssignment));
        // Materialization order: the entries loop (redundant header entry) always runs before
        // createSplitOwnerAssignments - matching real insertion order.
        when(approvalAssignmentRepository.findByLevelInstance_InstanceId(level2.getInstanceId()))
                .thenReturn(List.of(redundantHeaderAssignment, engineeringSplitAssignment, hrSplitAssignment));
        when(approvalLevelInstanceRepository.findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(report.getReportId(), 1))
                .thenReturn(List.of(level1, level2));

        service.applyCorrectnessPasses(report, 1);

        assertThat(reportingManagerAssignment.getStatus()).isEqualTo(AssignmentStatus.PENDING);
        assertThat(redundantHeaderAssignment.getStatus())
                .as("The redundant header entry IS a genuine cross-level duplicate and should be skipped")
                .isEqualTo(AssignmentStatus.SKIPPED);
        assertThat(engineeringSplitAssignment.getStatus())
                .as("The genuine Engineering split-owner assignment must survive - it must never be skipped merely because an already-eliminated duplicate happened to share its approver")
                .isEqualTo(AssignmentStatus.PENDING);
        assertThat(hrSplitAssignment.getStatus()).isEqualTo(AssignmentStatus.PENDING);
    }
}
