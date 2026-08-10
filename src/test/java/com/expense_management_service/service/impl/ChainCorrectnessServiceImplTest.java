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
}
