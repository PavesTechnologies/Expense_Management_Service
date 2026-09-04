package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.EscalationRunResponse;
import com.expense_management_service.entity.ApprovalTask;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.ApprovalMode;
import com.expense_management_service.enums.TaskStatus;
import com.expense_management_service.repository.ApprovalTaskRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.service.DelegationService;
import com.expense_management_service.service.SlaPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// LENIENT: the shared save(any()) stub in setUp() is unused by tests where nothing is escalated
// (no overdue tasks, or no escalation target - the latter explicitly asserts save() is NEVER called).
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class EscalationServiceImplTest {

    @Mock private ApprovalTaskRepository approvalTaskRepository;
    @Mock private EmployeeCacheRepository employeeCacheRepository;
    @Mock private DelegationService delegationService;
    @Mock private SlaPolicyService slaPolicyService;

    private EscalationServiceImpl escalationService;
    private final List<ApprovalTask> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        escalationService = new EscalationServiceImpl(
                approvalTaskRepository, employeeCacheRepository, delegationService, slaPolicyService);
        saved.clear();
        when(approvalTaskRepository.save(any())).thenAnswer(inv -> {
            ApprovalTask t = inv.getArgument(0);
            if (t.getTaskId() == null) {
                t.setTaskId(UUID.randomUUID());
            }
            saved.add(t);
            return t;
        });
    }

    private ApprovalTask overdueTask(String approverId) {
        UUID groupId = UUID.randomUUID();
        return ApprovalTask.builder()
                .taskId(UUID.randomUUID())
                .report(ExpenseReport.builder().reportId(UUID.randomUUID()).build())
                .approverId(approverId)
                .approvalLevel(2)
                .groupId(groupId)
                .approvalMode(ApprovalMode.SEQUENTIAL)
                .submissionCycle(1)
                .taskStatus(TaskStatus.PENDING)
                .assignedAt(LocalDateTime.now().minusDays(5))
                .dueDate(LocalDateTime.now().minusDays(1))
                .build();
    }

    @Test
    void runEscalationSweep_escalatesToActiveDelegate_whenOneExists() {
        ApprovalTask overdue = overdueTask("mgr-jane");
        when(approvalTaskRepository.findByTaskStatusAndDueDateBefore(eq(TaskStatus.PENDING), any()))
                .thenReturn(List.of(overdue));
        when(delegationService.resolveActiveDelegate("mgr-jane")).thenReturn(Optional.of("mgr-alex"));
        when(slaPolicyService.resolveSlaBusinessDays()).thenReturn(3);

        EscalationRunResponse response = escalationService.runEscalationSweep();

        assertThat(response.overdueScanned()).isEqualTo(1);
        assertThat(response.escalated()).isEqualTo(1);
        assertThat(response.stalledNoTarget()).isZero();
        assertThat(overdue.getTaskStatus()).isEqualTo(TaskStatus.ESCALATED);
        assertThat(overdue.getActedBy()).isEqualTo("SYSTEM");
        assertThat(overdue.getActionedAt()).isNotNull();

        ApprovalTask replacement = saved.stream().filter(t -> t != overdue).findFirst().orElseThrow();
        assertThat(replacement.getApproverId()).isEqualTo("mgr-alex");
        assertThat(replacement.getTaskStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(replacement.getGroupId()).isEqualTo(overdue.getGroupId());
        assertThat(replacement.getApprovalLevel()).isEqualTo(overdue.getApprovalLevel());
        assertThat(replacement.getSubmissionCycle()).isEqualTo(overdue.getSubmissionCycle());
        assertThat(replacement.getDueDate()).isAfter(LocalDateTime.now());
        verify(employeeCacheRepository, never()).findByEmployeeId(any());
    }

    @Test
    void runEscalationSweep_escalatesToManager_whenNoActiveDelegateExists() {
        ApprovalTask overdue = overdueTask("mgr-jane");
        when(approvalTaskRepository.findByTaskStatusAndDueDateBefore(eq(TaskStatus.PENDING), any()))
                .thenReturn(List.of(overdue));
        when(delegationService.resolveActiveDelegate("mgr-jane")).thenReturn(Optional.empty());
        when(employeeCacheRepository.findByEmployeeId("mgr-jane"))
                .thenReturn(Optional.of(EmployeeCache.builder().employeeId("mgr-jane").managerEmployeeId("dir-sam").build()));
        when(slaPolicyService.resolveSlaBusinessDays()).thenReturn(3);

        escalationService.runEscalationSweep();

        ApprovalTask replacement = saved.stream().filter(t -> t != overdue).findFirst().orElseThrow();
        assertThat(replacement.getApproverId()).isEqualTo("dir-sam");
    }

    @Test
    void runEscalationSweep_leavesTaskAssignedAndUnescalated_whenNoTargetExists() {
        ApprovalTask overdue = overdueTask("mgr-jane");
        when(approvalTaskRepository.findByTaskStatusAndDueDateBefore(eq(TaskStatus.PENDING), any()))
                .thenReturn(List.of(overdue));
        when(delegationService.resolveActiveDelegate("mgr-jane")).thenReturn(Optional.empty());
        when(employeeCacheRepository.findByEmployeeId("mgr-jane")).thenReturn(Optional.empty());

        EscalationRunResponse response = escalationService.runEscalationSweep();

        assertThat(response.escalated()).isZero();
        assertThat(response.stalledNoTarget()).isEqualTo(1);
        assertThat(overdue.getTaskStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(overdue.getApproverId()).isEqualTo("mgr-jane");
        verify(approvalTaskRepository, never()).save(any());
    }

    @Test
    void runEscalationSweep_returnsZeroCounts_whenNoOverdueTasks() {
        when(approvalTaskRepository.findByTaskStatusAndDueDateBefore(eq(TaskStatus.PENDING), any()))
                .thenReturn(List.of());

        EscalationRunResponse response = escalationService.runEscalationSweep();

        assertThat(response.overdueScanned()).isZero();
        assertThat(response.escalated()).isZero();
        assertThat(response.stalledNoTarget()).isZero();
    }
}
