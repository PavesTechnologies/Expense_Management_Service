package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.ApprovalTaskResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.entity.ApprovalMatrix;
import com.expense_management_service.entity.ApprovalTask;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.ApprovalMode;
import com.expense_management_service.enums.ApproverType;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.enums.TaskStatus;
import com.expense_management_service.mapper.ApprovalTaskMapper;
import com.expense_management_service.mapper.ExpenseReportMapper;
import com.expense_management_service.repository.ApprovalMatrixRepository;
import com.expense_management_service.repository.ApprovalTaskRepository;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.service.ApproverResolver;
import com.expense_management_service.service.DelegationService;
import com.expense_management_service.service.ExchangeRateService;
import com.expense_management_service.service.SlaPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

// LENIENT: the shared setUp() below stubs the full set of collaborators needed by submit()'s
// happy path, but many tests deliberately exercise a guard clause that returns before some of
// those stubs are ever consulted (e.g. "not draft" never reaches currency conversion at all,
// "no line items" never reaches it either, getMyQueue() doesn't touch ExpenseReportRepository).
// Strict stubbing would flag each of those as unused; lenient is the correct tool here, not
// fragmenting the shared setup per test.
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class ApprovalWorkflowServiceImplTest {

    @Mock private ExpenseReportRepository expenseReportRepository;
    @Mock private ApprovalTaskRepository approvalTaskRepository;
    @Mock private ApprovalMatrixRepository approvalMatrixRepository;
    @Mock private CurrencyRepository currencyRepository;
    @Mock private ExchangeRateService exchangeRateService;
    @Mock private ApproverResolver approverResolver;
    @Mock private DelegationService delegationService;
    @Mock private SlaPolicyService slaPolicyService;

    private ApprovalWorkflowServiceImpl service;

    private final List<ApprovalTask> savedTasks = new ArrayList<>();
    private ExpenseReport report;
    private UUID reportId;
    private UUID costCenterId;

    @BeforeEach
    void setUp() {
        service = new ApprovalWorkflowServiceImpl(
                expenseReportRepository, approvalTaskRepository, approvalMatrixRepository,
                currencyRepository, exchangeRateService,
                approverResolver, delegationService, slaPolicyService, new ExpenseReportMapper(), new ApprovalTaskMapper());

        reportId = UUID.randomUUID();
        costCenterId = UUID.randomUUID();
        CostCenter costCenter = CostCenter.builder().costCenterId(costCenterId).ownerEmployeeId("cc-owner").build();
        Currency currency = Currency.builder().currencyId(UUID.randomUUID()).currencyCode("INR").decimalPlaces(2).build();

        report = ExpenseReport.builder()
                .reportId(reportId)
                .employeeId("EMP-1")
                .costCenter(costCenter)
                .currency(currency)
                .totalAmount(BigDecimal.valueOf(1000))
                .reportStatus(ReportStatus.DRAFT)
                .expenseLineItems(List.of(new ExpenseLineItem()))
                .build();

        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(expenseReportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(currencyRepository.findByCurrencyCode(any())).thenReturn(Optional.of(currency));
        when(exchangeRateService.convertAmount(any(), any(), any(), any())).thenReturn(BigDecimal.valueOf(1000));
        when(slaPolicyService.resolveSlaBusinessDays()).thenReturn(3);
        when(approverResolver.resolve(any(ApprovalMatrix.class), anyString()))
                .thenAnswer(inv -> Optional.of(((ApprovalMatrix) inv.getArgument(0)).getApproverReference()));
        // Default: only the assigned approver can act (mirrors the old plain-equality check).
        // Delegate-specific tests below override this per-test.
        when(delegationService.canAct(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0).equals(inv.getArgument(1)));

        savedTasks.clear();
        when(approvalTaskRepository.save(any())).thenAnswer(inv -> {
            ApprovalTask t = inv.getArgument(0);
            if (t.getTaskId() == null) {
                t.setTaskId(UUID.randomUUID());
            }
            savedTasks.removeIf(x -> x.getTaskId().equals(t.getTaskId()));
            savedTasks.add(t);
            return t;
        });
        when(approvalTaskRepository.findByReport_ReportIdOrderByApprovalLevelAsc(any())).thenAnswer(inv ->
                savedTasks.stream()
                        .filter(t -> t.getReport().getReportId().equals(inv.getArgument(0)))
                        .sorted(Comparator.comparing(ApprovalTask::getApprovalLevel))
                        .toList());
        when(approvalTaskRepository.findByGroupId(any())).thenAnswer(inv ->
                savedTasks.stream().filter(t -> inv.getArgument(0).equals(t.getGroupId())).toList());
        when(approvalTaskRepository.findById(any())).thenAnswer(inv ->
                savedTasks.stream().filter(t -> t.getTaskId().equals(inv.getArgument(0))).findFirst());
    }

    private ApprovalMatrix matrixRow(int level, String approverRef, ApprovalMode mode) {
        return ApprovalMatrix.builder()
                .matrixId(UUID.randomUUID())
                .costCenter(report.getCostCenter())
                .approvalLevel(level)
                .approverType(ApproverType.USER)
                .approverReference(approverRef)
                .approvalMode(mode)
                .status("ACTIVE")
                .build();
    }

    private void givenMatrix(ApprovalMatrix... rows) {
        when(approvalMatrixRepository.findByCostCenter_CostCenterIdAndStatusOrderByApprovalLevelAsc(costCenterId, "ACTIVE"))
                .thenReturn(List.of(rows));
    }

    private List<ApprovalTask> tasksAtLevel(int level) {
        return savedTasks.stream().filter(t -> t.getApprovalLevel() == level).toList();
    }

    // ---- submit() ----

    @Test
    void submit_materialisesFullChain_sequentialParallelAllThenSequential() {
        givenMatrix(
                matrixRow(1, "mgr-jane", ApprovalMode.SEQUENTIAL),
                matrixRow(2, "fin-alex", ApprovalMode.PARALLEL_ALL),
                matrixRow(2, "fin-priya", ApprovalMode.PARALLEL_ALL),
                matrixRow(3, "dir-sam", ApprovalMode.SEQUENTIAL));

        ExpenseReportResponse response = service.submit(reportId);

        assertThat(response.reportStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(savedTasks).hasSize(4);

        List<ApprovalTask> level1 = tasksAtLevel(1);
        assertThat(level1).hasSize(1);
        assertThat(level1.get(0).getTaskStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(level1.get(0).getAssignedAt()).isNotNull();
        assertThat(level1.get(0).getDueDate()).isNotNull();

        List<ApprovalTask> level2 = tasksAtLevel(2);
        assertThat(level2).hasSize(2);
        assertThat(level2).allMatch(t -> t.getTaskStatus() == TaskStatus.QUEUED);
        assertThat(level2).allMatch(t -> t.getAssignedAt() == null && t.getDueDate() == null);
        assertThat(level2.get(0).getGroupId()).isEqualTo(level2.get(1).getGroupId());

        List<ApprovalTask> level3 = tasksAtLevel(3);
        assertThat(level3).hasSize(1);
        assertThat(level3.get(0).getTaskStatus()).isEqualTo(TaskStatus.QUEUED);
    }

    @Test
    void submit_autoSkipsDuplicateApprover_acrossLevels() {
        givenMatrix(
                matrixRow(1, "mgr-jane", ApprovalMode.SEQUENTIAL),
                matrixRow(2, "mgr-jane", ApprovalMode.SEQUENTIAL));

        service.submit(reportId);

        assertThat(tasksAtLevel(1).get(0).getTaskStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(tasksAtLevel(2).get(0).getTaskStatus()).isEqualTo(TaskStatus.SKIPPED);
    }

    @Test
    void approve_skipsOverAFullySkippedLevel_andActivatesTheOneAfterIt() {
        givenMatrix(
                matrixRow(1, "mgr-jane", ApprovalMode.SEQUENTIAL),
                matrixRow(2, "mgr-jane", ApprovalMode.SEQUENTIAL), // duplicate of level 1 -> SKIPPED
                matrixRow(3, "dir-sam", ApprovalMode.SEQUENTIAL));
        service.submit(reportId);
        assertThat(tasksAtLevel(2).get(0).getTaskStatus()).isEqualTo(TaskStatus.SKIPPED);
        ApprovalTask level1Task = tasksAtLevel(1).get(0);

        service.approve(level1Task.getTaskId(), "mgr-jane", null);

        assertThat(tasksAtLevel(3).get(0).getTaskStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.PENDING_APPROVAL);
    }

    @Test
    void approve_autoApprovesReport_whenEveryRemainingLevelIsFullySkipped() {
        givenMatrix(
                matrixRow(1, "mgr-jane", ApprovalMode.SEQUENTIAL),
                matrixRow(2, "mgr-jane", ApprovalMode.SEQUENTIAL)); // duplicate of level 1, and the last level
        service.submit(reportId);
        ApprovalTask level1Task = tasksAtLevel(1).get(0);

        service.approve(level1Task.getTaskId(), "mgr-jane", null);

        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.APPROVED);
        assertThat(report.getApprovedAt()).isNotNull();
    }

    @Test
    void submit_throwsIllegalArgumentException_whenReportIsNotDraft() {
        report.setReportStatus(ReportStatus.PENDING_APPROVAL);

        assertThatThrownBy(() -> service.submit(reportId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DRAFT");
    }

    @Test
    void submit_throwsIllegalArgumentException_whenNoLineItems() {
        report.setExpenseLineItems(List.of());

        assertThatThrownBy(() -> service.submit(reportId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line items");
    }

    @Test
    void submit_throwsIllegalArgumentException_whenNoLevel1ApproverConfigured() {
        givenMatrix(matrixRow(2, "fin-alex", ApprovalMode.SEQUENTIAL));

        assertThatThrownBy(() -> service.submit(reportId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Level 1");
    }

    @Test
    void submit_throwsIllegalArgumentException_whenApproverCannotBeResolved() {
        givenMatrix(matrixRow(1, "mgr-jane", ApprovalMode.SEQUENTIAL));
        when(approverResolver.resolve(any(), anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(reportId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unable to resolve");
    }

    // ---- approve() ----

    @Test
    void approve_sequentialLevel_activatesNextLevel() {
        givenMatrix(
                matrixRow(1, "mgr-jane", ApprovalMode.SEQUENTIAL),
                matrixRow(2, "dir-sam", ApprovalMode.SEQUENTIAL));
        service.submit(reportId);
        ApprovalTask level1Task = tasksAtLevel(1).get(0);

        ApprovalTaskResponse response = service.approve(level1Task.getTaskId(), "mgr-jane", "looks good");

        assertThat(response.taskStatus()).isEqualTo("APPROVED");
        assertThat(tasksAtLevel(2).get(0).getTaskStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(tasksAtLevel(2).get(0).getDueDate()).isNotNull();
    }

    @Test
    void approve_lastLevel_movesReportToApproved() {
        givenMatrix(matrixRow(1, "mgr-jane", ApprovalMode.SEQUENTIAL));
        service.submit(reportId);
        ApprovalTask task = tasksAtLevel(1).get(0);

        service.approve(task.getTaskId(), "mgr-jane", null);

        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.APPROVED);
        assertThat(report.getApprovedAt()).isNotNull();
    }

    @Test
    void approve_parallelAny_cancelsRemainingPendingSiblings_onFirstApproval() {
        givenMatrix(matrixRow(1, "fin-alex", ApprovalMode.PARALLEL_ANY), matrixRow(1, "fin-priya", ApprovalMode.PARALLEL_ANY));
        service.submit(reportId);
        ApprovalTask alexTask = tasksAtLevel(1).stream().filter(t -> t.getApproverId().equals("fin-alex")).findFirst().orElseThrow();
        ApprovalTask priyaTask = tasksAtLevel(1).stream().filter(t -> t.getApproverId().equals("fin-priya")).findFirst().orElseThrow();
        assertThat(alexTask.getTaskStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(priyaTask.getTaskStatus()).isEqualTo(TaskStatus.PENDING);

        service.approve(alexTask.getTaskId(), "fin-alex", null);

        assertThat(priyaTask.getTaskStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.APPROVED);
    }

    @Test
    void approve_parallelAll_doesNotCompleteLevel_untilEverySiblingApproves() {
        givenMatrix(matrixRow(1, "fin-alex", ApprovalMode.PARALLEL_ALL), matrixRow(1, "fin-priya", ApprovalMode.PARALLEL_ALL));
        service.submit(reportId);
        ApprovalTask alexTask = tasksAtLevel(1).stream().filter(t -> t.getApproverId().equals("fin-alex")).findFirst().orElseThrow();
        ApprovalTask priyaTask = tasksAtLevel(1).stream().filter(t -> t.getApproverId().equals("fin-priya")).findFirst().orElseThrow();

        service.approve(alexTask.getTaskId(), "fin-alex", null);

        // priya's task must still be PENDING - the level isn't complete, report must not advance.
        assertThat(priyaTask.getTaskStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.PENDING_APPROVAL);

        service.approve(priyaTask.getTaskId(), "fin-priya", null);

        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.APPROVED);
    }

    @Test
    void approve_parallelAll_completesLevel_whenASiblingWasSkippedAsADuplicate() {
        // Regression: a same-level duplicate approver (fin-alex configured twice at level 1) is
        // SKIPPED, not APPROVED. isLevelComplete must not require a SKIPPED sibling to also
        // become APPROVED - that can never happen, and would leave the level permanently stuck.
        givenMatrix(
                matrixRow(1, "fin-alex", ApprovalMode.PARALLEL_ALL),
                matrixRow(1, "fin-alex", ApprovalMode.PARALLEL_ALL),
                matrixRow(1, "fin-priya", ApprovalMode.PARALLEL_ALL));
        service.submit(reportId);
        List<ApprovalTask> level1 = tasksAtLevel(1);
        ApprovalTask skipped = level1.stream().filter(t -> t.getTaskStatus() == TaskStatus.SKIPPED).findFirst().orElseThrow();
        ApprovalTask alexTask = level1.stream()
                .filter(t -> t.getApproverId().equals("fin-alex") && t != skipped).findFirst().orElseThrow();
        ApprovalTask priyaTask = level1.stream().filter(t -> t.getApproverId().equals("fin-priya")).findFirst().orElseThrow();

        service.approve(alexTask.getTaskId(), "fin-alex", null);
        service.approve(priyaTask.getTaskId(), "fin-priya", null);

        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.APPROVED);
    }

    @Test
    void approve_throwsIllegalArgumentException_whenTaskIsNotPending() {
        givenMatrix(matrixRow(1, "mgr-jane", ApprovalMode.SEQUENTIAL));
        service.submit(reportId);
        ApprovalTask task = tasksAtLevel(1).get(0);
        task.setTaskStatus(TaskStatus.APPROVED);

        assertThatThrownBy(() -> service.approve(task.getTaskId(), "mgr-jane", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approve_throwsAccessDeniedException_whenCallerIsNotTheAssignedApprover() {
        givenMatrix(matrixRow(1, "mgr-jane", ApprovalMode.SEQUENTIAL));
        service.submit(reportId);
        ApprovalTask task = tasksAtLevel(1).get(0);

        assertThatThrownBy(() -> service.approve(task.getTaskId(), "someone-else", null))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---- delegation (Phase 3) ----

    @Test
    void approve_succeeds_whenActingUserIsAnActiveDelegate_andRecordsActedBy() {
        givenMatrix(matrixRow(1, "mgr-jane", ApprovalMode.SEQUENTIAL));
        service.submit(reportId);
        ApprovalTask task = tasksAtLevel(1).get(0);
        when(delegationService.canAct("mgr-alex", "mgr-jane")).thenReturn(true);

        ApprovalTaskResponse response = service.approve(task.getTaskId(), "mgr-alex", "covering for jane");

        assertThat(response.taskStatus()).isEqualTo("APPROVED");
        // approverId must NOT change - the delegate acted on Jane's task, they didn't become its owner.
        assertThat(task.getApproverId()).isEqualTo("mgr-jane");
        assertThat(task.getActedBy()).isEqualTo("mgr-alex");
    }

    @Test
    void approve_throwsAccessDeniedException_whenDelegationServiceSaysNoDelegateIsActive() {
        givenMatrix(matrixRow(1, "mgr-jane", ApprovalMode.SEQUENTIAL));
        service.submit(reportId);
        ApprovalTask task = tasksAtLevel(1).get(0);
        when(delegationService.canAct("mgr-alex", "mgr-jane")).thenReturn(false);

        assertThatThrownBy(() -> service.approve(task.getTaskId(), "mgr-alex", null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void approve_leavesActedByNull_whenTheAssignedApproverActsDirectly() {
        givenMatrix(matrixRow(1, "mgr-jane", ApprovalMode.SEQUENTIAL));
        service.submit(reportId);
        ApprovalTask task = tasksAtLevel(1).get(0);

        service.approve(task.getTaskId(), "mgr-jane", null);

        assertThat(task.getActedBy()).isNull();
    }

    // ---- reject() ----

    @Test
    void reject_revertsReportToDraft_andCancelsLaterQueuedLevels() {
        givenMatrix(
                matrixRow(1, "mgr-jane", ApprovalMode.SEQUENTIAL),
                matrixRow(2, "dir-sam", ApprovalMode.SEQUENTIAL));
        service.submit(reportId);
        ApprovalTask task = tasksAtLevel(1).get(0);

        service.reject(task.getTaskId(), "mgr-jane", "not compliant");

        assertThat(task.getTaskStatus()).isEqualTo(TaskStatus.REJECTED);
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.DRAFT);
        assertThat(tasksAtLevel(2).get(0).getTaskStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void reject_cancelsAlreadyApprovedSibling_inAllRequiredParallelGroup() {
        givenMatrix(matrixRow(1, "fin-alex", ApprovalMode.PARALLEL_ALL), matrixRow(1, "fin-priya", ApprovalMode.PARALLEL_ALL));
        service.submit(reportId);
        ApprovalTask alexTask = tasksAtLevel(1).stream().filter(t -> t.getApproverId().equals("fin-alex")).findFirst().orElseThrow();
        ApprovalTask priyaTask = tasksAtLevel(1).stream().filter(t -> t.getApproverId().equals("fin-priya")).findFirst().orElseThrow();
        service.approve(alexTask.getTaskId(), "fin-alex", null);
        assertThat(alexTask.getTaskStatus()).isEqualTo(TaskStatus.APPROVED);

        service.reject(priyaTask.getTaskId(), "fin-priya", "found an issue");

        assertThat(alexTask.getTaskStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.DRAFT);
    }

    // ---- getMyQueue() ----

    @Test
    void getMyQueue_returnsOnlyPendingTasksForGivenApprover() {
        ApprovalTask pending = ApprovalTask.builder().taskId(UUID.randomUUID()).report(report)
                .approverId("mgr-jane").taskStatus(TaskStatus.PENDING).build();
        when(approvalTaskRepository.findByApproverIdAndTaskStatus("mgr-jane", TaskStatus.PENDING))
                .thenReturn(List.of(pending));

        List<ApprovalTaskResponse> queue = service.getMyQueue("mgr-jane");

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).approverId()).isEqualTo("mgr-jane");
    }
}
