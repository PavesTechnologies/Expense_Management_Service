package com.expense_management_service.service.impl;

import com.expense_management_service.common.BusinessDayCalculator;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.response.ApprovalTaskResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.entity.ApprovalMatrix;
import com.expense_management_service.entity.ApprovalTask;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.ApprovalMode;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.enums.TaskStatus;
import com.expense_management_service.mapper.ApprovalTaskMapper;
import com.expense_management_service.mapper.ExpenseReportMapper;
import com.expense_management_service.repository.ApprovalMatrixRepository;
import com.expense_management_service.repository.ApprovalTaskRepository;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.service.ApprovalWorkflowService;
import com.expense_management_service.service.ApproverResolver;
import com.expense_management_service.service.DelegationService;
import com.expense_management_service.service.ExchangeRateService;
import com.expense_management_service.service.SlaPolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ApprovalWorkflowServiceImpl implements ApprovalWorkflowService {

    private static final String MATRIX_STATUS_ACTIVE = "ACTIVE";

    private final ExpenseReportRepository expenseReportRepository;
    private final ApprovalTaskRepository approvalTaskRepository;
    private final ApprovalMatrixRepository approvalMatrixRepository;
    private final CurrencyRepository currencyRepository;
    private final ExchangeRateService exchangeRateService;
    private final ApproverResolver approverResolver;
    private final DelegationService delegationService;
    private final SlaPolicyService slaPolicyService;
    private final ExpenseReportMapper expenseReportMapper;
    private final ApprovalTaskMapper approvalTaskMapper;

    @Value("${exchange.rate.base-currency}")
    private String baseCurrencyCode;

    @Override
    public ExpenseReportResponse submit(UUID reportId) {
        ExpenseReport report = findReport(reportId);
        assertDraft(report);
        assertHasLineItems(report);
        if (report.getCostCenter() == null) {
            throw new IllegalArgumentException("Expense report has no cost center assigned");
        }

        BigDecimal convertedAmount = convertToBaseCurrency(report);
        List<ApprovalMatrix> applicable = resolveApplicableMatrixRows(report.getCostCenter().getCostCenterId(), convertedAmount);

        List<Integer> levels = applicable.stream()
                .map(ApprovalMatrix::getApprovalLevel)
                .distinct()
                .sorted()
                .toList();

        if (levels.isEmpty() || !levels.get(0).equals(1)) {
            throw new IllegalArgumentException(
                    "No active Level 1 approver is configured for cost center "
                            + report.getCostCenter().getCostCenterId() + " at this amount (VAL-08)");
        }

        Map<Integer, List<ApprovalMatrix>> byLevel = applicable.stream()
                .collect(Collectors.groupingBy(ApprovalMatrix::getApprovalLevel));

        int cycle = nextSubmissionCycle(reportId);
        materializeChain(report, byLevel, levels, cycle);

        report.setReportStatus(ReportStatus.PENDING_APPROVAL);
        report.setSubmittedAt(LocalDateTime.now());
        expenseReportRepository.save(report);

        activateNextEligibleLevel(report, null, cycle);

        log.info("Submitted expense report {} for approval (cycle {}), {} level(s) resolved", reportId, cycle, levels.size());
        return expenseReportMapper.toResponse(findReport(reportId));
    }

    @Override
    public ApprovalTaskResponse approve(UUID taskId, String actingEmployeeId, String comments) {
        ApprovalTask task = findTask(taskId);
        assertPendingAndAuthorized(task, actingEmployeeId);

        task.setTaskStatus(TaskStatus.APPROVED);
        task.setActionedAt(LocalDateTime.now());
        task.setComments(comments);
        stampActedByIfDelegate(task, actingEmployeeId);
        approvalTaskRepository.save(task);

        if (isLevelComplete(task)) {
            cancelRemainingPendingSiblings(task);
            activateNextEligibleLevel(task.getReport(), task.getApprovalLevel(), task.getSubmissionCycle());
        }

        return approvalTaskMapper.toResponse(findTask(taskId));
    }

    @Override
    public ApprovalTaskResponse reject(UUID taskId, String actingEmployeeId, String comments) {
        ApprovalTask task = findTask(taskId);
        assertPendingAndAuthorized(task, actingEmployeeId);

        task.setTaskStatus(TaskStatus.REJECTED);
        task.setActionedAt(LocalDateTime.now());
        task.setComments(comments);
        stampActedByIfDelegate(task, actingEmployeeId);
        approvalTaskRepository.save(task);

        // Rejection wins immediately: every other sibling in this level - including an already
        // APPROVED one, in an ALL-required group - and every later-level task still QUEUED is cancelled.
        approvalTaskRepository.findByGroupId(task.getGroupId()).stream()
                .filter(sibling -> !sibling.getTaskId().equals(task.getTaskId()))
                .filter(sibling -> sibling.getTaskStatus() == TaskStatus.PENDING
                        || sibling.getTaskStatus() == TaskStatus.QUEUED
                        || sibling.getTaskStatus() == TaskStatus.APPROVED)
                .forEach(this::cancel);

        approvalTaskRepository.findByReport_ReportIdOrderByApprovalLevelAsc(task.getReport().getReportId()).stream()
                .filter(t -> Objects.equals(t.getSubmissionCycle(), task.getSubmissionCycle()))
                .filter(t -> t.getApprovalLevel() > task.getApprovalLevel())
                .filter(t -> t.getTaskStatus() == TaskStatus.QUEUED)
                .forEach(this::cancel);

        ExpenseReport report = task.getReport();
        report.setReportStatus(ReportStatus.DRAFT);
        expenseReportRepository.save(report);

        return approvalTaskMapper.toResponse(findTask(taskId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApprovalTaskResponse> getMyQueue(String employeeId) {
        return approvalTaskRepository.findByApproverIdAndTaskStatus(employeeId, TaskStatus.PENDING).stream()
                .map(approvalTaskMapper::toResponse)
                .toList();
    }

    // ---------------------------------------------------------------------
    // Submission helpers
    // ---------------------------------------------------------------------

    private BigDecimal convertToBaseCurrency(ExpenseReport report) {
        Currency baseCurrency = currencyRepository.findByCurrencyCode(baseCurrencyCode)
                .orElseThrow(() -> new IllegalStateException(
                        "Configured base currency '" + baseCurrencyCode + "' does not exist in the Currency master table"));
        return exchangeRateService.convertAmount(
                report.getTotalAmount(), report.getCurrency().getCurrencyId(), baseCurrency.getCurrencyId(), LocalDate.now());
    }

    private List<ApprovalMatrix> resolveApplicableMatrixRows(UUID costCenterId, BigDecimal convertedAmount) {
        return approvalMatrixRepository
                .findByCostCenter_CostCenterIdAndStatusOrderByApprovalLevelAsc(costCenterId, MATRIX_STATUS_ACTIVE)
                .stream()
                .filter(m -> m.getMinimumAmount() == null || convertedAmount.compareTo(m.getMinimumAmount()) >= 0)
                .filter(m -> m.getMaximumAmount() == null || convertedAmount.compareTo(m.getMaximumAmount()) <= 0)
                .toList();
    }

    private int nextSubmissionCycle(UUID reportId) {
        return approvalTaskRepository.findByReport_ReportIdOrderByApprovalLevelAsc(reportId).stream()
                .map(ApprovalTask::getSubmissionCycle)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .map(cycle -> cycle + 1)
                .orElse(1);
    }

    /**
     * Materialises every resolved level as ApprovalTask rows in QUEUED status (duplicate
     * approvers as SKIPPED) - this one pass IS the "snapshot at submission": the whole chain is
     * frozen as rows immediately, immune to later ApprovalMatrix edits. Nothing is PENDING yet;
     * {@link #activateNextEligibleLevel} does that in a second pass.
     */
    private void materializeChain(ExpenseReport report, Map<Integer, List<ApprovalMatrix>> byLevel,
                                  List<Integer> levels, int cycle) {
        java.util.Set<String> resolvedApproverIds = new java.util.HashSet<>();

        for (Integer level : levels) {
            UUID groupId = UUID.randomUUID();
            for (ApprovalMatrix matrixRow : byLevel.get(level)) {
                String approverId = approverResolver.resolve(matrixRow, report.getEmployeeId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unable to resolve an approver for level " + level + " (" + matrixRow.getApproverType()
                                        + " " + matrixRow.getApproverReference() + ") and no default approver is configured"));

                ApprovalTask.ApprovalTaskBuilder builder = ApprovalTask.builder()
                        .report(report)
                        .approverId(approverId)
                        .approvalLevel(level)
                        .groupId(groupId)
                        .approvalMode(matrixRow.getApprovalMode())
                        .submissionCycle(cycle);

                if (resolvedApproverIds.contains(approverId)) {
                    builder.taskStatus(TaskStatus.SKIPPED)
                            .comments("Auto-skipped: " + approverId + " already appears earlier in this approval chain");
                } else {
                    builder.taskStatus(TaskStatus.QUEUED);
                    resolvedApproverIds.add(approverId);
                }

                approvalTaskRepository.save(builder.build());
            }
        }
    }

    /**
     * Advances from {@code afterLevel} (exclusive; {@code null} means "from the beginning") to
     * the first level with at least one QUEUED task, activating it (QUEUED -> PENDING, stamping
     * assignedAt/dueDate - the SLA clock starts only now). A level that is fully SKIPPED is
     * itself skipped over. If every remaining level is fully skipped, the report is approved outright.
     */
    private void activateNextEligibleLevel(ExpenseReport report, Integer afterLevel, int cycle) {
        List<ApprovalTask> tasks = approvalTaskRepository
                .findByReport_ReportIdOrderByApprovalLevelAsc(report.getReportId()).stream()
                .filter(t -> t.getSubmissionCycle() != null && t.getSubmissionCycle() == cycle)
                .filter(t -> afterLevel == null || t.getApprovalLevel() > afterLevel)
                .toList();

        Map<Integer, List<ApprovalTask>> byLevel = tasks.stream()
                .collect(Collectors.groupingBy(ApprovalTask::getApprovalLevel));

        for (Integer level : byLevel.keySet().stream().sorted().toList()) {
            List<ApprovalTask> queuedAtLevel = byLevel.get(level).stream()
                    .filter(t -> t.getTaskStatus() == TaskStatus.QUEUED)
                    .toList();
            if (queuedAtLevel.isEmpty()) {
                continue; // fully resolved via auto-skip, nothing to activate at this level
            }

            LocalDateTime now = LocalDateTime.now();
            int slaDays = slaPolicyService.resolveSlaBusinessDays();
            for (ApprovalTask task : queuedAtLevel) {
                task.setTaskStatus(TaskStatus.PENDING);
                task.setAssignedAt(now);
                task.setDueDate(BusinessDayCalculator.addBusinessDays(now, slaDays));
                approvalTaskRepository.save(task);
            }
            return;
        }

        // No remaining level had anything to activate - every one auto-resolved via skip.
        report.setReportStatus(ReportStatus.APPROVED);
        report.setApprovedAt(LocalDateTime.now());
        expenseReportRepository.save(report);
    }

    private boolean isLevelComplete(ApprovalTask task) {
        if (task.getApprovalMode() != ApprovalMode.PARALLEL_ALL) {
            return true; // SEQUENTIAL and PARALLEL_ANY: one approval always completes the level
        }
        // SKIPPED (a same-level duplicate approver) and ESCALATED (superseded by a replacement
        // task sharing this groupId - see EscalationService) never needed a real approval of
        // their own; only genuine votes (APPROVED) or their absence should block completion.
        return approvalTaskRepository.findByGroupId(task.getGroupId()).stream()
                .allMatch(sibling -> sibling.getTaskStatus() == TaskStatus.APPROVED
                        || sibling.getTaskStatus() == TaskStatus.SKIPPED
                        || sibling.getTaskStatus() == TaskStatus.ESCALATED);
    }

    private void cancelRemainingPendingSiblings(ApprovalTask task) {
        approvalTaskRepository.findByGroupId(task.getGroupId()).stream()
                .filter(sibling -> sibling.getTaskStatus() == TaskStatus.PENDING)
                .forEach(this::cancel);
    }

    private void cancel(ApprovalTask task) {
        task.setTaskStatus(TaskStatus.CANCELLED);
        approvalTaskRepository.save(task);
    }

    private void assertDraft(ExpenseReport report) {
        if (report.getReportStatus() != ReportStatus.DRAFT) {
            throw new IllegalArgumentException(
                    "Expense report must be in DRAFT status to submit, current status: " + report.getReportStatus());
        }
    }

    private void assertHasLineItems(ExpenseReport report) {
        if (report.getExpenseLineItems() == null || report.getExpenseLineItems().isEmpty()) {
            throw new IllegalArgumentException("Expense report has no line items");
        }
    }

    private void assertPendingAndAuthorized(ApprovalTask task, String actingEmployeeId) {
        if (task.getTaskStatus() != TaskStatus.PENDING) {
            throw new IllegalArgumentException("Approval task is not pending, current status: " + task.getTaskStatus());
        }
        if (!delegationService.canAct(actingEmployeeId, task.getApproverId())) {
            throw new AccessDeniedException("You are not the assigned approver for this task, nor an active delegate");
        }
    }

    /**
     * approverId is never rewritten (see DelegationService) - when a delegate acted, actedBy
     * records who actually did, preserving an accurate "X approved on behalf of Y" audit trail.
     */
    private void stampActedByIfDelegate(ApprovalTask task, String actingEmployeeId) {
        task.setActedBy(actingEmployeeId.equals(task.getApproverId()) ? null : actingEmployeeId);
    }

    private ExpenseReport findReport(UUID reportId) {
        return expenseReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseReport not found with id: " + reportId));
    }

    private ApprovalTask findTask(UUID taskId) {
        return approvalTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("ApprovalTask not found with id: " + taskId));
    }
}
