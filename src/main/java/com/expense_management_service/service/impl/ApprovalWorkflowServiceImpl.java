package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.LineItemReviewRequest;
import com.expense_management_service.dto.request.RejectReportRequest;
import com.expense_management_service.dto.response.ApprovalQueueItemResponse;
import com.expense_management_service.dto.response.ApprovalStatusResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.dto.response.LineItemReviewResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.dto.response.PendingLineItemResponse;
import com.expense_management_service.entity.ApprovalAssignment;
import com.expense_management_service.entity.ApprovalFlow;
import com.expense_management_service.entity.ApprovalLevel;
import com.expense_management_service.entity.ApprovalLevelApprover;
import com.expense_management_service.entity.ApprovalLevelInstance;
import com.expense_management_service.entity.ApprovalLineItemReview;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.AssignmentStatus;
import com.expense_management_service.enums.LevelInstanceStatus;
import com.expense_management_service.enums.LevelQuorum;
import com.expense_management_service.enums.LineItemReviewStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.mapper.ApprovalFlowMapper;
import com.expense_management_service.mapper.ExpenseReportMapper;
import com.expense_management_service.mapper.PolicyViolationMapper;
import com.expense_management_service.repository.ApprovalAssignmentRepository;
import com.expense_management_service.repository.ApprovalLevelInstanceRepository;
import com.expense_management_service.repository.ApprovalLineItemReviewRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.PolicyViolationRepository;
import com.expense_management_service.service.ApprovalEventPublisher;
import com.expense_management_service.service.ApprovalFlowResolutionService;
import com.expense_management_service.service.ApprovalWorkflowService;
import com.expense_management_service.service.ApproverSourceResolver;
import com.expense_management_service.service.ChainCorrectnessService;
import com.expense_management_service.service.DelegationService;
import com.expense_management_service.service.PolicyDecision;
import com.expense_management_service.service.PolicyEvaluationGateway;
import com.expense_management_service.service.SlaPolicyService;
import com.expense_management_service.common.BusinessDayCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The new Approval Flow Engine orchestrator. Replaces EP06's {@code ApprovalWorkflowServiceImpl}
 * (cost-center + amount-range matrix) entirely.
 * <p>
 * <b>Documented simplification on quorum + line-item granularity:</b> {@code ApprovalLineItemReview}
 * is keyed by (lineItem, levelInstance) - one shared review per line item per level, regardless of
 * how many approver entries that level has. This models SEQUENTIAL cleanly (each entryOrder gets its
 * own fresh pass over the line items - reviews reset to PENDING when their turn starts). ANY_OF is
 * exact ("first to complete a full pass wins"). ALL_OF is, for now, treated identically to ANY_OF -
 * true "every approver independently agrees on every line item" would need a review keyed by
 * (lineItem, levelInstance, assignment) instead of just (lineItem, levelInstance), which is a real
 * data-model change left for a future enhancement if strict ALL_OF-at-line-item-granularity is
 * actually needed.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ApprovalWorkflowServiceImpl implements ApprovalWorkflowService {

    private static final String MATRIX_STATUS_ACTIVE = "ACTIVE";
    /** Mirrors {@code ExpenseLineItemServiceImpl}'s line-item status constants — see {@link #refreshPolicyViolationsForReport}. */
    private static final String LINE_ITEM_STATUS_ACTIVE = "ACTIVE";
    private static final String LINE_ITEM_STATUS_BLOCKED = "BLOCKED";


    private final ExpenseReportRepository expenseReportRepository;
    private final ApprovalLevelInstanceRepository approvalLevelInstanceRepository;
    private final ApprovalAssignmentRepository approvalAssignmentRepository;
    private final ApprovalLineItemReviewRepository approvalLineItemReviewRepository;
    private final PolicyViolationRepository policyViolationRepository;
    private final ApprovalFlowResolutionService approvalFlowResolutionService;
    private final ApproverSourceResolver approverSourceResolver;
    private final ChainCorrectnessService chainCorrectnessService;
    private final DelegationService delegationService;
    private final PolicyEvaluationGateway policyEvaluationGateway;
    private final ApprovalEventPublisher approvalEventPublisher;
    private final ExpenseReportMapper expenseReportMapper;
    private final SlaPolicyService slaPolicyService;
    private final PolicyViolationMapper policyViolationMapper;

    // ---------------------------------------------------------------------
    // Submission / resubmission
    // ---------------------------------------------------------------------

    @Override
    public ExpenseReportResponse submit(UUID reportId) {
        ExpenseReport report = findReport(reportId);

        if (report.getReportStatus() == ReportStatus.AWAITING_CORRECTION) {
            return resubmitCorrection(report);
        }

        assertDraft(report);
        assertHasLineItems(report);
        if (report.getCostCenter() == null) {
            throw new IllegalArgumentException("Expense report has no cost center assigned");
        }

        PolicyDecision decision = policyEvaluationGateway.evaluate(report);
        if (!decision.allowed()) {
            throw new IllegalArgumentException("Submission blocked by Policy Engine: " + decision.violations());
        }

        ApprovalFlow flow = approvalFlowResolutionService.resolveMatchingFlow(report);
        int cycle = nextSubmissionCycle(reportId);

        materializeChain(report, flow, cycle);
        chainCorrectnessService.applyCorrectnessPasses(report, cycle);

        report.setReportStatus(ReportStatus.PENDING_APPROVAL);
        report.setSubmittedAt(LocalDateTime.now());
        expenseReportRepository.save(report);

        activateNextEligibleLevel(report, cycle, null);
        approvalEventPublisher.publish("REPORT_SUBMITTED", reportId, "flow=" + flow.getFlowId() + " cycle=" + cycle);

        log.info("Submitted expense report {} for approval (cycle {}, flow {})", reportId, cycle, flow.getFlowId());
        return toResponse(findReport(reportId));
    }

    /** Employee resubmits after Needs Correction (§2.8/§4.3): resume in place if the same flow still matches, else full restart. */
    private ExpenseReportResponse resubmitCorrection(ExpenseReport report) {
        PolicyDecision decision = policyEvaluationGateway.evaluate(report);
        if (!decision.allowed()) {
            throw new IllegalArgumentException("Resubmission blocked by Policy Engine: " + decision.violations());
        }

        int currentCycle = currentSubmissionCycle(report.getReportId());
        var currentInstances = approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(report.getReportId(), currentCycle);
        UUID currentFlowId = currentInstances.isEmpty() ? null : currentInstances.get(0).getFlowId();

        ApprovalFlow rematchedFlow = approvalFlowResolutionService.resolveMatchingFlow(report);

        if (rematchedFlow.getFlowId().equals(currentFlowId)) {
            return resumeInPlace(report, currentCycle);
        }
        return fullRestart(report, rematchedFlow);
    }

    private ExpenseReportResponse resumeInPlace(ExpenseReport report, int cycle) {
        var activeInstance = approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleAndStatus(report.getReportId(), cycle, LevelInstanceStatus.ACTIVE)
                .orElseThrow(() -> new IllegalStateException(
                        "Report " + report.getReportId() + " is AWAITING_CORRECTION but has no ACTIVE level instance"));

        approvalLineItemReviewRepository.findByLevelInstance_InstanceIdAndStatus(
                        activeInstance.getInstanceId(), LineItemReviewStatus.NEEDS_CORRECTION)
                .forEach(review -> {
                    review.setStatus(LineItemReviewStatus.PENDING);
                    approvalLineItemReviewRepository.save(review);
                });

        report.setReportStatus(ReportStatus.PENDING_APPROVAL);
        expenseReportRepository.save(report);
        approvalEventPublisher.publish("REPORT_RESUMED", report.getReportId(), "level=" + activeInstance.getLevelOrder());

        log.info("Report {} resumed in place at level {} (same flow still matches)", report.getReportId(), activeInstance.getLevelOrder());
        return toResponse(findReport(report.getReportId()));
    }

    private ExpenseReportResponse fullRestart(ExpenseReport report, ApprovalFlow newFlow) {
        int oldCycle = currentSubmissionCycle(report.getReportId());
        approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(report.getReportId(), oldCycle)
                .forEach(instance -> {
                    if (instance.getStatus() != LevelInstanceStatus.COMPLETED) {
                        instance.setStatus(LevelInstanceStatus.CANCELLED);
                        approvalLevelInstanceRepository.save(instance);
                    }
                });

        int newCycle = oldCycle + 1;
        materializeChain(report, newFlow, newCycle);
        chainCorrectnessService.applyCorrectnessPasses(report, newCycle);

        report.setReportStatus(ReportStatus.PENDING_APPROVAL);
        expenseReportRepository.save(report);
        activateNextEligibleLevel(report, newCycle, null);
        approvalEventPublisher.publish("REPORT_RESTARTED", report.getReportId(), "newFlow=" + newFlow.getFlowId() + " cycle=" + newCycle);

        log.info("Report {} full-restarted (a corrected line changed the matched flow): cycle {} -> {}, flow -> {}",
                report.getReportId(), oldCycle, newCycle, newFlow.getFlowId());
        return toResponse(findReport(report.getReportId()));
    }

    // ---------------------------------------------------------------------
    // Recall / Cancel (§6) - one unified restriction: blocked once any level has approved
    // ---------------------------------------------------------------------

    @Override
    public ExpenseReportResponse recall(UUID reportId, String actingEmployeeId) {
        ExpenseReport report = findReport(reportId);
        assertOwner(report, actingEmployeeId);
        if (report.getReportStatus() != ReportStatus.PENDING_APPROVAL && report.getReportStatus() != ReportStatus.AWAITING_CORRECTION) {
            throw new IllegalArgumentException("Only a report Pending Approval or Awaiting Correction may be recalled");
        }
        if (hasAnyLevelApproved(report)) {
            throw new IllegalArgumentException("Cannot recall or cancel - at least one approval level has already completed");
        }

        cancelAllOpenInstances(report, currentSubmissionCycle(reportId));
        report.setReportStatus(ReportStatus.DRAFT);
        expenseReportRepository.save(report);
        approvalEventPublisher.publish("REPORT_RECALLED", reportId, "by=" + actingEmployeeId);

        log.info("Report {} recalled to DRAFT by {}", reportId, actingEmployeeId);
        return toResponse(findReport(reportId));
    }

    @Override
    public ExpenseReportResponse cancel(UUID reportId, String actingEmployeeId) {
        ExpenseReport report = findReport(reportId);
        assertOwner(report, actingEmployeeId);
        if (report.getReportStatus() == ReportStatus.APPROVED || report.getReportStatus() == ReportStatus.REJECTED
                || report.getReportStatus() == ReportStatus.CANCELLED) {
            throw new IllegalArgumentException("Cannot cancel a report that already reached a final outcome");
        }
        if (hasAnyLevelApproved(report)) {
            throw new IllegalArgumentException("Cannot recall or cancel - at least one approval level has already completed");
        }

        if (report.getReportStatus() != ReportStatus.DRAFT) {
            cancelAllOpenInstances(report, currentSubmissionCycle(reportId));
        }
        report.setReportStatus(ReportStatus.CANCELLED);
        expenseReportRepository.save(report);
        approvalEventPublisher.publish("REPORT_CANCELLED", reportId, "by=" + actingEmployeeId);

        log.info("Report {} cancelled by {}", reportId, actingEmployeeId);
        return toResponse(findReport(reportId));
    }

    // ---------------------------------------------------------------------
    // Line-item review (§4.7) - the real unit of approver action
    // ---------------------------------------------------------------------

    @Override
    public ExpenseReportResponse reviewLineItem(UUID reportId, UUID lineItemId, String actingEmployeeId, LineItemReviewRequest request) {
        if (request.decision() == LineItemReviewStatus.PENDING) {
            throw new IllegalArgumentException("decision must be APPROVED or NEEDS_CORRECTION");
        }
        if (request.decision() == LineItemReviewStatus.NEEDS_CORRECTION
                && (request.comment() == null || request.comment().isBlank())) {
            throw new IllegalArgumentException("A comment is required when flagging a line item as Needs Correction");
        }

        ExpenseReport report = findReport(reportId);
        int cycle = currentSubmissionCycle(reportId);
        ApprovalLevelInstance activeInstance = approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, cycle, LevelInstanceStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("Report " + reportId + " has no level currently active for review"));

        ApprovalAssignment authorizing = approvalAssignmentRepository.findByLevelInstance_InstanceId(activeInstance.getInstanceId())
                .stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE)
                .filter(a -> delegationService.canAct(actingEmployeeId, a.getApproverId()))
                .findFirst()
                .orElseThrow(() -> new AccessDeniedException(
                        "You are not an active approver (or delegate) for this report's current level"));

        ApprovalLineItemReview review = approvalLineItemReviewRepository
                .findByLineItem_LineItemIdAndLevelInstance_InstanceId(lineItemId, activeInstance.getInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException("No pending review for line item " + lineItemId + " at this level"));
        if (review.getStatus() != LineItemReviewStatus.PENDING) {
            throw new IllegalArgumentException("This line item has already been reviewed at this level: " + review.getStatus());
        }

        review.setStatus(request.decision());
        review.setComment(request.comment());
        review.setActedBy(actingEmployeeId.equals(authorizing.getApproverId()) ? null : actingEmployeeId);
        review.setActionedAt(LocalDateTime.now());
        approvalLineItemReviewRepository.save(review);
        approvalEventPublisher.publish("LINE_ITEM_REVIEWED", reportId,
                "lineItem=" + lineItemId + " decision=" + request.decision() + " by=" + actingEmployeeId);

        if (request.decision() == LineItemReviewStatus.NEEDS_CORRECTION) {
            report.setReportStatus(ReportStatus.AWAITING_CORRECTION);
            expenseReportRepository.save(report);
            approvalEventPublisher.publish("REPORT_AWAITING_CORRECTION", reportId, "lineItem=" + lineItemId);
            return toResponse(findReport(reportId));
        }

        if (isInstanceFullyApproved(activeInstance)) {
            completeLevelOrAdvanceSequential(report, activeInstance, authorizing, cycle);
        }
        return toResponse(findReport(reportId));
    }

    // ---------------------------------------------------------------------
    // Whole-report Reject (§6) - terminal, distinct from Needs Correction
    // ---------------------------------------------------------------------

    @Override
    public ExpenseReportResponse rejectReport(UUID reportId, String actingEmployeeId, RejectReportRequest request) {
        ExpenseReport report = findReport(reportId);
        int cycle = currentSubmissionCycle(reportId);
        ApprovalLevelInstance activeInstance = approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, cycle, LevelInstanceStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("Report " + reportId + " has no level currently active"));

        boolean authorized = approvalAssignmentRepository.findByLevelInstance_InstanceId(activeInstance.getInstanceId())
                .stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE)
                .anyMatch(a -> delegationService.canAct(actingEmployeeId, a.getApproverId()));
        if (!authorized) {
            throw new AccessDeniedException("You are not an active approver (or delegate) for this report's current level");
        }

        cancelAllOpenInstances(report, cycle);
        report.setReportStatus(ReportStatus.REJECTED);
        report.setRejectedBy(actingEmployeeId);
        report.setRejectionComment(request.comment());
        report.setRejectedAt(LocalDateTime.now());
        expenseReportRepository.save(report);
        approvalEventPublisher.publish("REPORT_REJECTED", reportId, "by=" + actingEmployeeId + " comment=" + request.comment());

        log.info("Report {} rejected (terminal) by {} - comment: {}", reportId, actingEmployeeId, request.comment());
        return toResponse(findReport(reportId));
    }

    // ---------------------------------------------------------------------
    // My Queue (§1.5/§9.1) - presence-based
    // ---------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ApprovalQueueItemResponse> getMyQueue(String actingEmployeeId, Pageable pageable) {
        Set<String> approverIds = delegationService.resolveApproverIdsActingFor(actingEmployeeId);

        Page<UUID> reportIdsPage = approvalAssignmentRepository
                .findDistinctReportIdsByStatusAndApproverIdIn(AssignmentStatus.ACTIVE, approverIds, pageable);

        List<ApprovalQueueItemResponse> items = reportIdsPage.getContent().stream()
                .map(reportId -> representativeAssignment(reportId, approverIds))
                .flatMap(Optional::stream)
                .map(this::toQueueItem)
                .toList();

        return PageResponse.of(new PageImpl<>(items, pageable, reportIdsPage.getTotalElements()));
    }

    /** The one ACTIVE assignment a queue row is built from for a given report - first match, same tie-break as the pre-pagination in-memory version. */
    private Optional<ApprovalAssignment> representativeAssignment(UUID reportId, Set<String> approverIds) {
        return approvalAssignmentRepository.findByLevelInstance_Report_ReportId(reportId).stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE)
                .filter(a -> approverIds.contains(a.getApproverId()))
                .findFirst();
    }

    private ApprovalQueueItemResponse toQueueItem(ApprovalAssignment assignment) {
        ApprovalLevelInstance instance = assignment.getLevelInstance();
        ExpenseReport report = instance.getReport();

        var pendingReviews = approvalLineItemReviewRepository
                .findByLevelInstance_InstanceIdAndStatus(instance.getInstanceId(), LineItemReviewStatus.PENDING);

        List<PendingLineItemResponse> pendingLineItems = pendingReviews.stream()
                .map(review -> {
                    ExpenseLineItem lineItem = review.getLineItem();
                    var violations = policyViolationRepository.findByLineItem_LineItemId(lineItem.getLineItemId()).stream()
                            .map(policyViolationMapper::toResponse)
                            .toList();
                    return new PendingLineItemResponse(
                            lineItem.getLineItemId(), review.getReviewId(),
                            lineItem.getCategory() != null ? lineItem.getCategory().getCategoryName() : null,
                            lineItem.getMerchantName(), lineItem.getDescription(), lineItem.getExpenseDate(),
                            lineItem.getAmount(), lineItem.getCurrency() != null ? lineItem.getCurrency().getCurrencyCode() : null,
                            violations);
                })
                .toList();

        boolean eligibleForBulkApprove = policyViolationRepository.findByLineItem_Report_ReportId(report.getReportId()).isEmpty();

        return new ApprovalQueueItemResponse(
                report.getReportId(), report.getReportNumber(), report.getEmployeeId(), report.getTotalAmount(),
                report.getCurrency() != null ? report.getCurrency().getCurrencyCode() : null,
                instance.getLevelOrder(), pendingLineItems, eligibleForBulkApprove);
    }

    // ---------------------------------------------------------------------
    // Bulk approve (§4.4/§10.3) - only for reports with zero pending flags
    // ---------------------------------------------------------------------

    @Override
    public ExpenseReportResponse bulkApprove(UUID reportId, String actingEmployeeId) {
        if (!policyViolationRepository.findByLineItem_Report_ReportId(reportId).isEmpty()) {
            throw new IllegalArgumentException("Report " + reportId + " has policy violations and is not eligible for bulk approval");
        }

        ExpenseReport report = findReport(reportId);
        int cycle = currentSubmissionCycle(reportId);
        ApprovalLevelInstance activeInstance = approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, cycle, LevelInstanceStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("Report " + reportId + " has no level currently active for review"));

        var pendingReviews = approvalLineItemReviewRepository
                .findByLevelInstance_InstanceIdAndStatus(activeInstance.getInstanceId(), LineItemReviewStatus.PENDING);
        if (pendingReviews.stream().anyMatch(r -> !policyViolationRepository.findByLineItem_LineItemId(r.getLineItem().getLineItemId()).isEmpty())) {
            throw new IllegalArgumentException("Report " + reportId + " has flagged line items and is not eligible for bulk approval");
        }

        for (ApprovalLineItemReview review : pendingReviews) {
            reviewLineItem(reportId, review.getLineItem().getLineItemId(), actingEmployeeId,
                    new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        }

        log.info("Bulk-approved {} line item(s) on report {} by {}", pendingReviews.size(), reportId, actingEmployeeId);
        return toResponse(findReport(reportId));
    }

    // ---------------------------------------------------------------------
    // Read models: correction visibility, status pill, history (§14 backend gaps)
    // ---------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<LineItemReviewResponse> getLineItemReviews(UUID reportId, String actingEmployeeId) {
        ExpenseReport report = findReport(reportId);
        assertCanViewLineItemReviews(report, actingEmployeeId);

        int cycle = currentSubmissionCycle(reportId);
        var instances = approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(reportId, cycle);

        List<LineItemReviewResponse> result = new java.util.ArrayList<>();
        for (ApprovalLevelInstance instance : instances) {
            for (ApprovalLineItemReview review : approvalLineItemReviewRepository.findByLevelInstance_InstanceId(instance.getInstanceId())) {
                result.add(new LineItemReviewResponse(
                        review.getLineItem().getLineItemId(),
                        review.getReviewId(),
                        review.getStatus(),
                        review.getComment(),
                        review.getActedBy(),
                        review.getActionedAt(),
                        instance.getLevelOrder(),
                        instance.getLevelName(),
                        ApprovalFlowMapper.resolveDisplayName(instance.getLevelName(), instance.getLevelOrder())));
            }
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public ApprovalStatusResponse getApprovalStatus(UUID reportId) {
        ExpenseReport report = findReport(reportId);
        int cycle = currentSubmissionCycle(reportId);
        var instances = approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(reportId, cycle);

        ApprovalLevelInstance active = instances.stream()
                .filter(i -> i.getStatus() == LevelInstanceStatus.ACTIVE)
                .findFirst().orElse(null);

        Integer currentLevelOrder = active != null ? active.getLevelOrder() : null;
        String currentLevelName = active != null ? active.getLevelName() : null;
        String displayName = active != null
                ? ApprovalFlowMapper.resolveDisplayName(active.getLevelName(), active.getLevelOrder())
                : null;

        return new ApprovalStatusResponse(currentLevelOrder, currentLevelName, displayName, instances.size(),
                isRecallEligible(report), isCancelEligible(report));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ExpenseReportResponse> getMyHistory(String actingEmployeeId, String outcome, Pageable pageable) {
        boolean includeApproved = outcome == null || outcome.equalsIgnoreCase("APPROVED");
        boolean includeRejected = outcome == null || outcome.equalsIgnoreCase("REJECTED");

        Page<ExpenseReport> page = expenseReportRepository
                .findHistoryForApprover(actingEmployeeId, includeApproved, includeRejected, pageable);
        return PageResponse.of(page.map(this::toResponse));
    }

    // ---------------------------------------------------------------------
    // Chain materialisation & activation
    // ---------------------------------------------------------------------

    /** Snapshot-at-submission (§3.1): materialises every level as a QUEUED instance up front; nothing is ACTIVE yet. */
    private void materializeChain(ExpenseReport report, ApprovalFlow flow, int cycle) {
        List<ApprovalLevel> levels = flow.getLevels().stream()
                .sorted(Comparator.comparing(ApprovalLevel::getLevelOrder))
                .toList();

        for (ApprovalLevel level : levels) {
            ApprovalLevelInstance instance = ApprovalLevelInstance.builder()
                    .report(report)
                    .flowId(flow.getFlowId())
                    .levelOrder(level.getLevelOrder())
                    .levelName(level.getLevelName())
                    .quorum(level.getQuorum())
                    .submissionCycle(cycle)
                    .status(LevelInstanceStatus.QUEUED)
                    .build();
            ApprovalLevelInstance savedInstance = approvalLevelInstanceRepository.save(instance);

            List<ApprovalLevelApprover> entries = level.getApprovers().stream()
                    .sorted(Comparator.comparing(ApprovalLevelApprover::getEntryOrder, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();

            for (ApprovalLevelApprover entry : entries) {
                approverSourceResolver.resolve(entry, report).ifPresent(approverId ->
                        approvalAssignmentRepository.save(ApprovalAssignment.builder()
                                .levelInstance(savedInstance)
                                .approverId(approverId)
                                .sourceType(entry.getSourceType())
                                .entryOrder(entry.getEntryOrder())
                                .status(AssignmentStatus.PENDING)
                                .build()));
            }

            if (approvalAssignmentRepository.findByLevelInstance_InstanceId(savedInstance.getInstanceId()).isEmpty()) {
                throw new IllegalStateException("Level " + level.getLevelOrder() + " of flow " + flow.getFlowId()
                        + " resolved zero approvers - check its approver-source configuration (e.g. a DEPARTMENT_OWNER "
                        + "with no DepartmentApprover mapping, or a COST_CENTER_OWNER with no owner set)");
            }
        }
    }

    /** Activates the next QUEUED level after {@code afterLevelOrder} (null = from the start). Report reaches APPROVED if none remain. */
    private void activateNextEligibleLevel(ExpenseReport report, int cycle, Integer afterLevelOrder) {
        var instances = approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(report.getReportId(), cycle);

        var nextQueued = instances.stream()
                .filter(i -> i.getStatus() == LevelInstanceStatus.QUEUED)
                .filter(i -> afterLevelOrder == null || i.getLevelOrder() > afterLevelOrder)
                .findFirst();

        if (nextQueued.isEmpty()) {
            completeReport(report);
            return;
        }
        activateLevelInstance(nextQueued.get(), report);
    }

    private void activateLevelInstance(ApprovalLevelInstance instance, ExpenseReport report) {
        instance.setStatus(LevelInstanceStatus.ACTIVE);
        approvalLevelInstanceRepository.save(instance);

        var assignments = approvalAssignmentRepository.findByLevelInstance_InstanceId(instance.getInstanceId()).stream()
                .filter(a -> a.getStatus() != AssignmentStatus.SKIPPED)
                .sorted(Comparator.comparing(ApprovalAssignment::getEntryOrder, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        if (instance.getQuorum() == LevelQuorum.SEQUENTIAL) {
            assignments.stream().findFirst().ifPresent(this::activateAssignment);
        } else {
            assignments.forEach(this::activateAssignment);
        }

        for (ExpenseLineItem lineItem : report.getExpenseLineItems()) {
            approvalLineItemReviewRepository.save(ApprovalLineItemReview.builder()
                    .lineItem(lineItem)
                    .levelInstance(instance)
                    .status(LineItemReviewStatus.PENDING)
                    .build());
        }
        approvalEventPublisher.publish("LEVEL_ACTIVATED", report.getReportId(), "level=" + instance.getLevelOrder());
    }

    /** SLA clock starts only now, not at materialisation (§5.4/§7.3) - mirrors EP06's exact same rule. */
    private void activateAssignment(ApprovalAssignment assignment) {
        LocalDateTime now = LocalDateTime.now();
        assignment.setStatus(AssignmentStatus.ACTIVE);
        assignment.setAssignedAt(now);
        assignment.setDueDate(BusinessDayCalculator.addBusinessDays(now, slaPolicyService.resolveSlaBusinessDays()));
        approvalAssignmentRepository.save(assignment);
    }

    private boolean isInstanceFullyApproved(ApprovalLevelInstance instance) {
        return approvalLineItemReviewRepository.findByLevelInstance_InstanceId(instance.getInstanceId()).stream()
                .allMatch(r -> r.getStatus() == LineItemReviewStatus.APPROVED);
    }

    /**
     * SEQUENTIAL: the completing assignment's entryOrder finishes; if another entry remains, it
     * becomes ACTIVE with a fresh pass (reviews reset to PENDING). ANY_OF/ALL_OF (documented
     * simplification, see class Javadoc): the level completes as soon as one pass finishes.
     */
    private void completeLevelOrAdvanceSequential(ExpenseReport report, ApprovalLevelInstance instance,
                                                   ApprovalAssignment completingAssignment, int cycle) {
        completingAssignment.setStatus(AssignmentStatus.COMPLETED);
        approvalAssignmentRepository.save(completingAssignment);

        if (instance.getQuorum() == LevelQuorum.SEQUENTIAL) {
            var remaining = approvalAssignmentRepository.findByLevelInstance_InstanceId(instance.getInstanceId()).stream()
                    .filter(a -> a.getStatus() == AssignmentStatus.PENDING)
                    .sorted(Comparator.comparing(ApprovalAssignment::getEntryOrder, Comparator.nullsLast(Comparator.naturalOrder())))
                    .findFirst();

            if (remaining.isPresent()) {
                ApprovalAssignment next = remaining.get();
                activateAssignment(next);

                approvalLineItemReviewRepository.findByLevelInstance_InstanceId(instance.getInstanceId())
                        .forEach(review -> {
                            review.setStatus(LineItemReviewStatus.PENDING);
                            approvalLineItemReviewRepository.save(review);
                        });
                approvalEventPublisher.publish("SEQUENTIAL_ENTRY_ADVANCED", report.getReportId(),
                        "level=" + instance.getLevelOrder() + " nextApprover=" + next.getApproverId());
                return;
            }
        } else {
            approvalAssignmentRepository.findByLevelInstance_InstanceId(instance.getInstanceId()).stream()
                    .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE || a.getStatus() == AssignmentStatus.PENDING)
                    .forEach(a -> {
                        a.setStatus(AssignmentStatus.COMPLETED);
                        approvalAssignmentRepository.save(a);
                    });
        }

        instance.setStatus(LevelInstanceStatus.COMPLETED);
        approvalLevelInstanceRepository.save(instance);
        approvalEventPublisher.publish("LEVEL_COMPLETED", report.getReportId(), "level=" + instance.getLevelOrder());

        activateNextEligibleLevel(report, cycle, instance.getLevelOrder());
    }

    /** §11.1: Reimbursement Tracking only ever receives one, single, fully-approved whole report. */
    private void completeReport(ExpenseReport report) {
        report.setReportStatus(ReportStatus.APPROVED);
        report.setApprovedAt(LocalDateTime.now());
        expenseReportRepository.save(report);
        approvalEventPublisher.publish("REPORT_APPROVED", report.getReportId(), "handoff=reimbursement-tracking");
        log.info("Report {} fully approved - handed off to Reimbursement Tracking", report.getReportId());
    }

    private void cancelAllOpenInstances(ExpenseReport report, int cycle) {
        approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(report.getReportId(), cycle)
                .forEach(instance -> {
                    if (instance.getStatus() != LevelInstanceStatus.COMPLETED) {
                        instance.setStatus(LevelInstanceStatus.CANCELLED);
                        approvalLevelInstanceRepository.save(instance);
                    }
                });
    }

    // ---------------------------------------------------------------------
    // Guards & helpers
    // ---------------------------------------------------------------------

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

    private void assertOwner(ExpenseReport report, String actingEmployeeId) {
        if (!Objects.equals(report.getEmployeeId(), actingEmployeeId)) {
            throw new AccessDeniedException("Only the report's owner may recall or cancel it");
        }
    }

    /** Recall/Cancel's one unified restriction (§6): blocked once any level has already approved. Shared with §status's canRecall/canCancel so the two never drift apart. */
    private boolean hasAnyLevelApproved(ExpenseReport report) {
        if (report.getReportStatus() == ReportStatus.DRAFT) {
            return false;
        }
        int cycle = currentSubmissionCycle(report.getReportId());
        return approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(report.getReportId(), cycle)
                .stream().anyMatch(i -> i.getStatus() == LevelInstanceStatus.COMPLETED);
    }

    private boolean isRecallEligible(ExpenseReport report) {
        boolean statusOk = report.getReportStatus() == ReportStatus.PENDING_APPROVAL || report.getReportStatus() == ReportStatus.AWAITING_CORRECTION;
        return statusOk && !hasAnyLevelApproved(report);
    }

    private boolean isCancelEligible(ExpenseReport report) {
        boolean statusOk = report.getReportStatus() != ReportStatus.APPROVED && report.getReportStatus() != ReportStatus.REJECTED
                && report.getReportStatus() != ReportStatus.CANCELLED;
        return statusOk && !hasAnyLevelApproved(report);
    }

    private void assertCanViewLineItemReviews(ExpenseReport report, String actingEmployeeId) {
        if (Objects.equals(report.getEmployeeId(), actingEmployeeId)) {
            return;
        }
        boolean everAssigned = approvalAssignmentRepository.findByLevelInstance_Report_ReportId(report.getReportId())
                .stream()
                .anyMatch(a -> delegationService.canAct(actingEmployeeId, a.getApproverId()));
        if (!everAssigned) {
            throw new AccessDeniedException("You may not view this report's approval history");
        }
    }

    private int nextSubmissionCycle(UUID reportId) {
        return currentSubmissionCycle(reportId) + 1;
    }

    /** 0 if the report has never been through the approval engine yet. */
    private int currentSubmissionCycle(UUID reportId) {
        return approvalLevelInstanceRepository.findMaxSubmissionCycle(reportId);
    }

    private ExpenseReport findReport(UUID reportId) {
        return expenseReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseReport not found with id: " + reportId));
    }

    private ExpenseReportResponse toResponse(ExpenseReport report) {
        int violations = policyViolationRepository.findByLineItem_Report_ReportId(report.getReportId()).size();
        int unjustified = (int) policyViolationRepository.findByLineItem_Report_ReportId(report.getReportId()).stream()
                .filter(v -> v.getJustification() == null).count();
        return expenseReportMapper.toResponse(report, report.getReportStatus().isEditable(), report.getReportStatus().isDeletable(),
                violations, unjustified);
    }
}
