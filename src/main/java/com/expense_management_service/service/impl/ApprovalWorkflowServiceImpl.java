package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.LineItemReviewRequest;
import com.expense_management_service.dto.request.RejectReportRequest;
import com.expense_management_service.dto.response.ApprovalQueueItemResponse;
import com.expense_management_service.dto.response.BudgetEncumbranceOutcome;
import com.expense_management_service.dto.response.BudgetWarning;
import com.expense_management_service.dto.response.ApprovalStatusResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.dto.response.LineItemReviewResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.dto.response.PendingLineItemResponse;
import com.expense_management_service.dto.response.PendingSplitResponse;
import com.expense_management_service.dto.response.SplitReviewResponse;
import com.expense_management_service.entity.ApprovalAssignment;
import com.expense_management_service.entity.ApprovalFlow;
import com.expense_management_service.entity.ApprovalLevel;
import com.expense_management_service.entity.ApprovalLevelApprover;
import com.expense_management_service.entity.ApprovalLevelInstance;
import com.expense_management_service.entity.ApprovalLineItemReview;
import com.expense_management_service.entity.ApprovalSplitReview;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.ExpenseSplit;
import com.expense_management_service.enums.ApproverSourceType;
import com.expense_management_service.enums.AssignmentStatus;
import com.expense_management_service.enums.LevelInstanceStatus;
import com.expense_management_service.enums.LevelQuorum;
import com.expense_management_service.enums.LevelType;
import com.expense_management_service.enums.LineItemReviewStatus;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.mapper.ApprovalFlowMapper;
import com.expense_management_service.mapper.PolicyViolationMapper;
import com.expense_management_service.repository.ApprovalAssignmentRepository;
import com.expense_management_service.repository.ApprovalLevelInstanceRepository;
import com.expense_management_service.repository.ApprovalLineItemReviewRepository;
import com.expense_management_service.repository.CashAdvanceAdjustmentRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.PolicyViolationRepository;
import com.expense_management_service.entity.FinanceVerificationReview;
import com.expense_management_service.enums.FinanceVerificationStatus;
import com.expense_management_service.repository.FinanceVerificationReviewRepository;
import com.expense_management_service.service.FinanceVerificationService;

import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceAdjustment;
import com.expense_management_service.service.ApprovalEventPublisher;
import com.expense_management_service.service.ApprovalFlowResolutionService;
import com.expense_management_service.service.ApprovalWorkflowService;
import com.expense_management_service.service.ApproverSourceResolver;
import com.expense_management_service.service.BudgetEncumbranceService;
import com.expense_management_service.service.ChainCorrectnessService;
import com.expense_management_service.service.DelegationService;
import com.expense_management_service.service.LevelReviewStrategy;
import com.expense_management_service.service.MaterialChangeEvaluator;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
    private final ApprovalSplitReviewRepository approvalSplitReviewRepository;
    private final PolicyViolationRepository policyViolationRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CashAdvanceAdjustmentRepository cashAdvanceAdjustmentRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CashAdvanceRepository cashAdvanceRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FinanceVerificationReviewRepository financeVerificationReviewRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private FinanceVerificationService financeVerificationService;

    private final ApprovalFlowResolutionService approvalFlowResolutionService;
    private final ApproverSourceResolver approverSourceResolver;
    private final ChainCorrectnessService chainCorrectnessService;
    private final BudgetEncumbranceService budgetEncumbranceService;
    private final DelegationService delegationService;
    private final PolicyEvaluationGateway policyEvaluationGateway;
    private final ApprovalEventPublisher approvalEventPublisher;
    private final SlaPolicyService slaPolicyService;
    private final PolicyViolationMapper policyViolationMapper;
    private final List<LevelReviewStrategy> levelReviewStrategies;
    private final ExpenseReportResponseFactory expenseReportResponseFactory;
    private final MaterialChangeEvaluator materialChangeEvaluator;

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

        logBudgetWarnings(budgetEncumbranceService.validateAndEncumber(report, cycle));
        materializeChain(report, flow, cycle);
        chainCorrectnessService.applyCorrectnessPasses(report, cycle);

        report.setReportStatus(ReportStatus.PENDING_APPROVAL);
        report.setSubmittedAt(LocalDateTime.now());
        expenseReportRepository.save(report);

        activateNextEligibleLevel(report, cycle, null);
        approvalEventPublisher.publish("REPORT_SUBMITTED", reportId, "flow=" + flow.getFlowId() + " cycle=" + cycle);
        updateCashAdvanceStatusForReportSubmission(report);

        log.info("Submitted expense report {} for approval (cycle {}, flow {})", reportId, cycle, flow.getFlowId());
        return toResponse(findReport(reportId));
    }

    /**
     * Employee resubmits after Needs Correction/Query (§2.8/§4.3). Manager-originated correction:
     * resume in place if the same flow still matches, else full restart - unchanged. Finance-
     * originated correction (Finance Verification): resume Finance in place only if BOTH the same
     * flow still matches AND {@code MaterialChangeEvaluator} finds no material change - otherwise
     * full restart back at Manager, since a client-billable flip (for one example) is never a
     * flow-matching criterion at all but must still force re-approval.
     */
    private ExpenseReportResponse resubmitCorrection(ExpenseReport report) {
        PolicyDecision decision = policyEvaluationGateway.evaluate(report);
        if (!decision.allowed()) {
            throw new IllegalArgumentException("Resubmission blocked by Policy Engine: " + decision.violations());
        }

        int currentCycle = currentSubmissionCycle(report.getReportId());
        var currentInstances = approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(report.getReportId(), currentCycle);
        UUID currentFlowId = currentInstances.isEmpty() ? null : currentInstances.get(0).getFlowId();
        ApprovalLevelInstance correctionSourceInstance = currentInstances.stream()
                .filter(i -> i.getStatus() == LevelInstanceStatus.ACTIVE)
                .findFirst()
                .orElse(null);
        LevelType correctionSourceLevelType = correctionSourceInstance != null ? correctionSourceInstance.getLevelType() : LevelType.APPROVAL;

        ApprovalFlow rematchedFlow = approvalFlowResolutionService.resolveMatchingFlow(report);
        boolean sameFlow = rematchedFlow.getFlowId().equals(currentFlowId);

        boolean restart;
        if (correctionSourceLevelType == LevelType.FINANCE_VERIFICATION) {
            boolean materialChange = correctionSourceInstance != null
                    && materialChangeEvaluator.hasMaterialChange(report, correctionSourceInstance);
            restart = !sameFlow || materialChange;
        } else {
            restart = !sameFlow;
        }

        return restart ? fullRestart(report, rematchedFlow) : resumeInPlace(report, currentCycle);
    }

    private ExpenseReportResponse resumeInPlace(ExpenseReport report, int cycle) {
        var activeInstance = approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleAndStatus(report.getReportId(), cycle, LevelInstanceStatus.ACTIVE)
                .orElseThrow(() -> new IllegalStateException(
                        "Report " + report.getReportId() + " is AWAITING_CORRECTION but has no ACTIVE level instance"));

        // Production-readiness audit, Parts 1-3: this is the ONLY correction path that can change
        // budget-impacting data (amounts, Cost Centers, added/removed splits) without going through
        // fullRestart's materialize-a-new-cycle path - resolveMatchingFlow re-matching the same flow
        // says nothing about whether the underlying splits changed. Must run BEFORE the existing
        // NEEDS_CORRECTION-only reset below, so a review reset here for a material change is not
        // then skipped by that narrower check.
        budgetEncumbranceService.reconcileForCycle(report, cycle);
        if (activeInstance.getLevelType() == LevelType.APPROVAL) {
            reconcileSplitOwnerApprovals(report, activeInstance);
        }

        resolveStrategy(activeInstance.getLevelType()).resumeCorrectedReviews(activeInstance);
        resumeCorrectedSplitReviews(activeInstance);

        report.setReportStatus(activeInstance.getLevelType() == LevelType.FINANCE_VERIFICATION
                ? ReportStatus.PENDING_FINANCE_VERIFICATION : ReportStatus.PENDING_APPROVAL);
        expenseReportRepository.save(report);
        approvalEventPublisher.publish("REPORT_RESUMED", report.getReportId(), "level=" + activeInstance.getLevelOrder());
        if (activeInstance.getLevelType() == LevelType.FINANCE_VERIFICATION) {
            approvalEventPublisher.publish("VERIFICATION_QUERY_RESOLVED", report.getReportId(), "level=" + activeInstance.getLevelOrder());
        }

        log.info("Report {} resumed in place at level {} (same flow still matches)", report.getReportId(), activeInstance.getLevelOrder());
        return toResponse(findReport(report.getReportId()));
    }

    private ExpenseReportResponse fullRestart(ExpenseReport report, ApprovalFlow newFlow) {
        int oldCycle = currentSubmissionCycle(report.getReportId());
        cancelAllOpenInstances(report, oldCycle);

        int newCycle = oldCycle + 1;
        logBudgetWarnings(budgetEncumbranceService.validateAndEncumber(report, newCycle));
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
        if (blocksRecall(report)) {
            throw new IllegalArgumentException(
                    "Cannot recall - at least one approval level has already completed, or Finance Verification is already active");
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
        if (activeInstance.getLevelType() != LevelType.APPROVAL) {
            if (activeInstance.getLevelType() == LevelType.FINANCE_VERIFICATION && financeVerificationService != null) {
                if (request.decision() == LineItemReviewStatus.APPROVED) {
                    return financeVerificationService.verifyLineItem(reportId, lineItemId, actingEmployeeId);
                } else if (request.decision() == LineItemReviewStatus.NEEDS_CORRECTION) {
                    return financeVerificationService.queryLineItem(reportId, lineItemId, actingEmployeeId, request.comment());
                }
            }
            throw new IllegalArgumentException("Report " + reportId + "'s active level is a Finance Verification level - "
                    + "use the Finance Verification API, not the generic approval review endpoint");
        }

        // Split-owner assignments (Phase 4) are deliberately excluded here - they exist ONLY to
        // track a Cost Center Owner's own splits, never as a stand-in completer for the shared
        // line-item review, even when the same person's approverId also happens to be resolvable
        // there. Without this exclusion, a split-owner reviewing an (also-shared) line item would
        // have completeLevelOrAdvanceSequential wrongly mark their OWN split-owner assignment
        // COMPLETED, even though their actual splits are still untouched.
        ApprovalAssignment authorizing = approvalAssignmentRepository.findByLevelInstance_InstanceId(activeInstance.getInstanceId())
                .stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE)
                .filter(a -> a.getSplitReviews().isEmpty())
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
    // Split review (Phase 4) - the Cost Center Owner's per-split decision, independent of
    // reviewLineItem. Keyed by (split, assignment) rather than a level-wide shared row, so one
    // owner's rejection of their own split can never block a different owner's (or the normal
    // track's) ability to act at the same level.
    // ---------------------------------------------------------------------

    @Override
    public ExpenseReportResponse reviewSplit(UUID reportId, UUID splitId, String actingEmployeeId, LineItemReviewRequest request) {
        if (request.decision() == LineItemReviewStatus.PENDING) {
            throw new IllegalArgumentException("decision must be APPROVED or NEEDS_CORRECTION");
        }
        if (request.decision() == LineItemReviewStatus.NEEDS_CORRECTION
                && (request.comment() == null || request.comment().isBlank())) {
            throw new IllegalArgumentException("A comment is required when flagging a split as Needs Correction");
        }

        ExpenseReport report = findReport(reportId);
        int cycle = currentSubmissionCycle(reportId);
        ApprovalLevelInstance activeInstance = approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, cycle, LevelInstanceStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("Report " + reportId + " has no level currently active for review"));
        if (activeInstance.getLevelType() != LevelType.APPROVAL) {
            throw new IllegalArgumentException("Report " + reportId + "'s active level is a Finance Verification level - "
                    + "splits are only reviewable at an Approval level");
        }

        ApprovalAssignment authorizing = approvalAssignmentRepository.findByLevelInstance_InstanceId(activeInstance.getInstanceId())
                .stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE)
                .filter(a -> !a.getSplitReviews().isEmpty())
                .filter(a -> a.getSplitReviews().stream().anyMatch(r -> r.getSplit().getSplitId().equals(splitId)))
                .filter(a -> delegationService.canAct(actingEmployeeId, a.getApproverId()))
                .findFirst()
                .orElseThrow(() -> new AccessDeniedException(
                        "You are not the active Cost Center Owner (or delegate) for this split at this report's current level"));

        ApprovalSplitReview review = approvalSplitReviewRepository
                .findBySplit_SplitIdAndAssignment_AssignmentId(splitId, authorizing.getAssignmentId())
                .orElseThrow(() -> new ResourceNotFoundException("No pending review for split " + splitId + " at this level"));
        if (review.getSplit().getRemovedAt() != null) {
            throw new IllegalArgumentException("This split was removed from the allocation during correction and no longer needs review.");
        }
        if (review.getStatus() != LineItemReviewStatus.PENDING) {
            throw new IllegalArgumentException("This split has already been reviewed at this level: " + review.getStatus());
        }

        review.setStatus(request.decision());
        review.setComment(request.comment());
        review.setActedBy(actingEmployeeId.equals(authorizing.getApproverId()) ? null : actingEmployeeId);
        review.setActionedAt(LocalDateTime.now());
        approvalSplitReviewRepository.save(review);
        approvalEventPublisher.publish("SPLIT_REVIEWED", reportId,
                "split=" + splitId + " decision=" + request.decision() + " by=" + actingEmployeeId);

        if (request.decision() == LineItemReviewStatus.NEEDS_CORRECTION) {
            report.setReportStatus(ReportStatus.AWAITING_CORRECTION);
            expenseReportRepository.save(report);
            approvalEventPublisher.publish("REPORT_AWAITING_CORRECTION", reportId, "split=" + splitId);
            return toResponse(findReport(reportId));
        }

        boolean assignmentDone = approvalSplitReviewRepository.findByAssignment_AssignmentId(authorizing.getAssignmentId())
                .stream().allMatch(r -> r.getStatus() == LineItemReviewStatus.APPROVED);
        if (assignmentDone) {
            completeSplitOwnerAssignment(report, activeInstance, authorizing, cycle);
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
                .map(reportId -> matchingAssignments(reportId, approverIds))
                .filter(assignments -> !assignments.isEmpty())
                .map(this::toQueueItem)
                .toList();

        return PageResponse.of(new PageImpl<>(items, pageable, reportIdsPage.getTotalElements()));
    }

    /**
     * Every ACTIVE assignment this caller (or a delegator they act for) currently holds on a given
     * report - production-readiness audit, Part 4: a caller can hold BOTH a normal-track assignment
     * AND one or more split-owner assignments on the same report at the same level, and the queue
     * row must surface every one of them, not just whichever came first. Previously only the first
     * match was used, silently hiding a dual-role caller's other responsibility.
     */
    private List<ApprovalAssignment> matchingAssignments(UUID reportId, Set<String> approverIds) {
        return approvalAssignmentRepository.findByLevelInstance_Report_ReportId(reportId).stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE)
                .filter(a -> approverIds.contains(a.getApproverId()))
                .toList();
    }

    private ApprovalQueueItemResponse toQueueItem(List<ApprovalAssignment> assignments) {
        ApprovalLevelInstance instance = assignments.get(0).getLevelInstance();
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

        // Scoped to the caller's OWN matched assignments, not the whole level - unlike
        // pendingLineItems (a level-wide shared review), split ownership is assignment-specific: a
        // different Cost Center Owner's splits must never appear in this caller's queue item. A
        // dual-role caller's normal-track assignment contributes none (empty splitReviews), so this
        // naturally aggregates only the split-owner assignment(s) among `assignments`.
        List<PendingSplitResponse> pendingSplits = assignments.stream()
                .flatMap(a -> a.getSplitReviews().stream())
                .filter(review -> review.getStatus() == LineItemReviewStatus.PENDING)
                .filter(review -> review.getSplit().getRemovedAt() == null)
                .map(review -> {
                    ExpenseSplit split = review.getSplit();
                    CostCenter costCenter = split.getCostCenter();
                    return new PendingSplitResponse(
                            split.getSplitId(), review.getReviewId(), split.getLineItem().getLineItemId(),
                            costCenter != null ? costCenter.getCostCenterId() : null,
                            costCenter != null ? costCenter.getCostCenterCode() : null,
                            costCenter != null ? costCenter.getCostCenterName() : null,
                            split.getAllocatedAmount());
                })
                .toList();

        boolean eligibleForBulkApprove = policyViolationRepository.findByLineItem_Report_ReportId(report.getReportId()).isEmpty();

        return new ApprovalQueueItemResponse(
                report.getReportId(), report.getReportNumber(), report.getEmployeeId(), report.getTotalAmount(),
                report.getCurrency() != null ? report.getCurrency().getCurrencyCode() : null,
                report.getCostCenter() != null ? report.getCostCenter().getCostCenterName() : null,
                report.getReportStatus() != null ? report.getReportStatus().name() : null,
                report.getSubmittedAt(),
                instance.getLevelOrder(), pendingLineItems, pendingSplits, eligibleForBulkApprove);
    }

    // ---------------------------------------------------------------------
    // Bulk approve (§4.4/§10.3) - only for reports with zero pending flags
    // ---------------------------------------------------------------------

    @Override
    public ExpenseReportResponse bulkApprove(UUID reportId, String actingEmployeeId) {
        if (!policyViolationRepository.findByLineItem_Report_ReportId(reportId).isEmpty()) {
            throw new IllegalArgumentException("Report " + reportId + " has policy violations and is not eligible for bulk approval");
        }

        int cycle = currentSubmissionCycle(reportId);
        ApprovalLevelInstance activeInstance = approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, cycle, LevelInstanceStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("Report " + reportId + " has no level currently active for review"));

        if (activeInstance.getLevelType() == LevelType.FINANCE_VERIFICATION) {
            if (financeVerificationReviewRepository != null && financeVerificationService != null) {
                var pendingFinanceReviews = financeVerificationReviewRepository
                        .findByLevelInstance_InstanceIdAndStatus(activeInstance.getInstanceId(), FinanceVerificationStatus.PENDING);
                for (FinanceVerificationReview review : pendingFinanceReviews) {
                    financeVerificationService.verifyLineItem(reportId, review.getLineItem().getLineItemId(), actingEmployeeId);
                }
            }
            log.info("Bulk-approved finance verification level on report {} by {}", reportId, actingEmployeeId);
            return toResponse(findReport(reportId));
        }

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
    public List<SplitReviewResponse> getSplitReviews(UUID reportId, String actingEmployeeId) {
        ExpenseReport report = findReport(reportId);
        assertCanViewLineItemReviews(report, actingEmployeeId);

        int cycle = currentSubmissionCycle(reportId);
        var instances = approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(reportId, cycle);

        List<SplitReviewResponse> result = new java.util.ArrayList<>();
        for (ApprovalLevelInstance instance : instances) {
            for (ApprovalAssignment assignment : approvalAssignmentRepository.findByLevelInstance_InstanceId(instance.getInstanceId())) {
                for (ApprovalSplitReview review : assignment.getSplitReviews()) {
                    ExpenseSplit split = review.getSplit();
                    CostCenter costCenter = split.getCostCenter();
                    result.add(new SplitReviewResponse(
                            split.getSplitId(),
                            review.getReviewId(),
                            split.getLineItem().getLineItemId(),
                            costCenter != null ? costCenter.getCostCenterId() : null,
                            costCenter != null ? costCenter.getCostCenterCode() : null,
                            review.getStatus(),
                            review.getComment(),
                            review.getActedBy(),
                            review.getActionedAt(),
                            instance.getLevelOrder(),
                            instance.getLevelName(),
                            ApprovalFlowMapper.resolveDisplayName(instance.getLevelName(), instance.getLevelOrder())));
                }
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
    // Re-entry point for non-APPROVAL level-type strategies (Finance Verification)
    // ---------------------------------------------------------------------

    @Override
    public void advanceAfterLevelReviewed(UUID reportId, UUID instanceId, String completingApproverId) {
        ExpenseReport report = findReport(reportId);
        ApprovalLevelInstance instance = approvalLevelInstanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("ApprovalLevelInstance not found with id: " + instanceId));

        if (!resolveStrategy(instance.getLevelType()).isLevelComplete(instance)) {
            return;
        }

        ApprovalAssignment completingAssignment = approvalAssignmentRepository.findByLevelInstance_InstanceId(instanceId)
                .stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE)
                .filter(a -> a.getApproverId().equals(completingApproverId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No ACTIVE assignment for approver " + completingApproverId + " at level instance " + instanceId));

        completeLevelOrAdvanceSequential(report, instance, completingAssignment, currentSubmissionCycle(reportId));
    }

    @Override
    @Transactional(readOnly = true)
    public int getCurrentSubmissionCycle(UUID reportId) {
        return currentSubmissionCycle(reportId);
    }

    // ---------------------------------------------------------------------
    // Chain materialisation & activation
    // ---------------------------------------------------------------------

    /** Snapshot-at-submission (§3.1): materialises every level as a QUEUED instance up front; nothing is ACTIVE yet. */
    private void materializeChain(ExpenseReport report, ApprovalFlow flow, int cycle) {
        List<ApprovalLevel> levels = flow.getLevels().stream()
                .sorted(Comparator.comparing(ApprovalLevel::getLevelOrder))
                .toList();

        boolean clientBillableAny = report.getExpenseLineItems() != null && report.getExpenseLineItems().stream()
                .anyMatch(li -> Boolean.TRUE.equals(li.getClientBillable()));
        String glAccountFingerprint = materialChangeEvaluator.computeGlAccountFingerprint(report);

        for (ApprovalLevel level : levels) {
            ApprovalLevelInstance instance = ApprovalLevelInstance.builder()
                    .report(report)
                    .flowId(flow.getFlowId())
                    .levelOrder(level.getLevelOrder())
                    .levelName(level.getLevelName())
                    .quorum(level.getQuorum())
                    .levelType(level.getLevelType())
                    .submissionCycle(cycle)
                    .status(LevelInstanceStatus.QUEUED)
                    .materializedTotalAmount(report.getTotalAmount())
                    .materializedCostCenterId(report.getCostCenter() != null ? report.getCostCenter().getCostCenterId() : null)
                    .materializedClientBillableAny(clientBillableAny)
                    .materializedGlAccountFingerprint(glAccountFingerprint)
                    .build();
            ApprovalLevelInstance savedInstance = approvalLevelInstanceRepository.save(instance);
        updateCashAdvanceStatusForReportReview(report);

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

            boolean hasCostCenterOwnerEntry = entries.stream().anyMatch(e -> e.getSourceType() == ApproverSourceType.COST_CENTER_OWNER);
            if (hasCostCenterOwnerEntry && level.getLevelType() == LevelType.APPROVAL) {
                createSplitOwnerAssignments(report, savedInstance);
            }

            if (approvalAssignmentRepository.findByLevelInstance_InstanceId(savedInstance.getInstanceId()).isEmpty()) {
                throw new IllegalStateException("Level " + level.getLevelOrder() + " of flow " + flow.getFlowId()
                        + " resolved zero approvers - check its approver-source configuration (e.g. a DEPARTMENT_OWNER "
                        + "with no DepartmentApprover mapping, or a COST_CENTER_OWNER with no owner set)");
            }
        }
    }

    /**
     * Split-aware {@code COST_CENTER_OWNER} resolution (Phase 4), Approval levels only. A no-op for
     * any report with zero {@code ExpenseSplit} rows - the level's normal, single header-cost-center
     * resolution (already handled by the entries loop above) is completely unaffected. When the
     * report DOES have splits, resolves one owner per distinct split cost center, combines every
     * split that resolves to the same owner (whether from the same line item or different ones)
     * into ONE {@code ApprovalAssignment}, and gives it one {@code ApprovalSplitReview} child per
     * split - additive alongside the header-cost-center assignment the entries loop may have also
     * created (covering any of this report's line items that are NOT split).
     */
    /** Every currently-active (non-removed) ExpenseSplit across the report's line items - a soft-deleted split (removedAt != null) is excluded from all live resolution, kept only so its historical ApprovalSplitReview can still resolve its FK. */
    private List<ExpenseSplit> activeSplits(ExpenseReport report) {
        return report.getExpenseLineItems() == null ? List.of()
                : report.getExpenseLineItems().stream()
                        .flatMap(li -> li.getExpenseSplits().stream())
                        .filter(s -> s.getRemovedAt() == null)
                        .toList();
    }

    private void createSplitOwnerAssignments(ExpenseReport report, ApprovalLevelInstance instance) {
        List<ExpenseSplit> allSplits = activeSplits(report);
        if (allSplits.isEmpty()) {
            return;
        }

        Map<String, List<ExpenseSplit>> splitsByOwner = new LinkedHashMap<>();
        for (ExpenseSplit split : allSplits) {
            String ownerId = split.getCostCenter() != null ? split.getCostCenter().getOwnerEmployeeId() : null;
            if (ownerId == null || ownerId.isBlank()) {
                log.warn("Split {} cost center {} has no ownerEmployeeId configured - skipping its Cost Center Owner assignment",
                        split.getSplitId(), split.getCostCenter() != null ? split.getCostCenter().getCostCenterId() : null);
                continue;
            }
            splitsByOwner.computeIfAbsent(ownerId, k -> new ArrayList<>()).add(split);
        }

        for (Map.Entry<String, List<ExpenseSplit>> ownerEntry : splitsByOwner.entrySet()) {
            ApprovalAssignment assignment = approvalAssignmentRepository.save(ApprovalAssignment.builder()
                    .levelInstance(instance)
                    .approverId(ownerEntry.getKey())
                    .sourceType(ApproverSourceType.COST_CENTER_OWNER)
                    .status(AssignmentStatus.PENDING)
                    .build());
            for (ExpenseSplit split : ownerEntry.getValue()) {
                ApprovalSplitReview review = approvalSplitReviewRepository.save(ApprovalSplitReview.builder()
                        .split(split)
                        .assignment(assignment)
                        .status(LineItemReviewStatus.PENDING)
                        .build());
                // Keep both sides of the association in sync in-memory - materializeChain and
                // activateNextEligibleLevel run in the SAME transaction as this save, and the
                // inverse mappedBy collection on `assignment` would otherwise still read back
                // empty for the rest of this call chain (JPA does not do this automatically).
                assignment.getSplitReviews().add(review);
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

        report.setReportStatus(instance.getLevelType() == LevelType.FINANCE_VERIFICATION
                ? ReportStatus.PENDING_FINANCE_VERIFICATION : ReportStatus.PENDING_APPROVAL);
        expenseReportRepository.save(report);

        var openAssignments = approvalAssignmentRepository.findByLevelInstance_InstanceId(instance.getInstanceId()).stream()
                .filter(a -> a.getStatus() != AssignmentStatus.SKIPPED)
                .toList();

        // Split-owner assignments (Phase 4) always activate immediately and act in parallel,
        // independent of the level's configured quorum - each owns a disjoint slice of the report
        // (their own splits), so there is nothing to sequence or race between them.
        var splitOwnerAssignments = openAssignments.stream().filter(a -> !a.getSplitReviews().isEmpty()).toList();
        var normalAssignments = openAssignments.stream().filter(a -> a.getSplitReviews().isEmpty())
                .sorted(Comparator.comparing(ApprovalAssignment::getEntryOrder, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        if (instance.getQuorum() == LevelQuorum.SEQUENTIAL) {
            normalAssignments.stream().findFirst().ifPresent(this::activateAssignment);
        } else {
            normalAssignments.forEach(this::activateAssignment);
        }
        splitOwnerAssignments.forEach(this::activateAssignment);

        resolveStrategy(instance.getLevelType()).createPendingReviews(instance, report.getExpenseLineItems());
        approvalEventPublisher.publish("LEVEL_ACTIVATED", report.getReportId(), "level=" + instance.getLevelOrder());
        if (instance.getLevelType() == LevelType.FINANCE_VERIFICATION) {
            approvalEventPublisher.publish("FINANCE_VERIFICATION_ACTIVATED", report.getReportId(), "level=" + instance.getLevelOrder());
        }
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
        return resolveStrategy(instance.getLevelType()).isLevelComplete(instance);
    }

    private LevelReviewStrategy resolveStrategy(LevelType levelType) {
        return levelReviewStrategies.stream()
                .filter(strategy -> strategy.levelType() == levelType)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No LevelReviewStrategy registered for level type " + levelType));
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

                resolveStrategy(instance.getLevelType()).resetPendingReviews(instance);
                approvalEventPublisher.publish("SEQUENTIAL_ENTRY_ADVANCED", report.getReportId(),
                        "level=" + instance.getLevelOrder() + " nextApprover=" + next.getApproverId());
                return;
            }
        } else {
            // Split-owner assignments (Phase 4) are deliberately excluded here - the documented
            // ANY_OF/ALL_OF simplification ("one pass finishes -> the shared line-item review is
            // done for everyone") applies only to the normal track. Each split-owner assignment
            // completes independently, only when its OWN split reviews are all approved (see
            // completeSplitOwnerAssignment / reviewSplit) - it must never be force-completed just
            // because a different, unrelated normal-track approver finished their own pass.
            approvalAssignmentRepository.findByLevelInstance_InstanceId(instance.getInstanceId()).stream()
                    .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE || a.getStatus() == AssignmentStatus.PENDING)
                    .filter(a -> a.getSplitReviews().isEmpty())
                    .forEach(a -> {
                        a.setStatus(AssignmentStatus.COMPLETED);
                        approvalAssignmentRepository.save(a);
                    });
        }

        finishInstanceIfFullyDone(report, instance, cycle);
    }

    /**
     * The normal (line-item) track has finished its own pass, or a split-owner assignment has just
     * finished its own splits - either way, only actually completes the level instance and advances
     * to the next level once BOTH tracks are done (Phase 4). For any level with no split-owner
     * assignments (the overwhelming majority, and every level before Phase 4), this reduces to
     * exactly the check the normal track already used, so behavior is unchanged.
     */
    private void finishInstanceIfFullyDone(ExpenseReport report, ApprovalLevelInstance instance, int cycle) {
        if (!isEntireInstanceDone(instance)) {
            return;
        }

        instance.setStatus(LevelInstanceStatus.COMPLETED);
        approvalLevelInstanceRepository.save(instance);
        approvalEventPublisher.publish("LEVEL_COMPLETED", report.getReportId(), "level=" + instance.getLevelOrder());
        if (instance.getLevelType() == LevelType.FINANCE_VERIFICATION) {
            approvalEventPublisher.publish("FINANCE_VERIFICATION_COMPLETED", report.getReportId(), "level=" + instance.getLevelOrder());
        }

        activateNextEligibleLevel(report, cycle, instance.getLevelOrder());
    }

    /**
     * Normal track's final pass done AND every non-skipped split-owner assignment's own splits are
     * all approved. A review whose split has since been removed from the allocation (production-
     * readiness audit, Part 2/3: {@code removedAt != null}) is excluded from this requirement,
     * exactly like a SKIPPED assignment's splits are - the split no longer exists to be approved.
     * <p>
     * Bug fix (production-readiness follow-up): the normal track's {@code isLevelComplete} gate
     * (every {@code ApprovalLineItemReview} at this level APPROVED - a level-wide shared review every
     * line item gets, split or not, deliberately requiring the normal-track approver to also sign off
     * a split line item's shared review alongside its split-owners' own allocation reviews) is only
     * even consulted when at least one normal-track assignment (one with no split reviews) at this
     * level actually SURVIVES (not SKIPPED). When a level's only normal-track assignment gets
     * legitimately skipped as a cross-level duplicate approver (§2.6) - e.g. the report's header Cost
     * Center happens to be one of its own split's Cost Centers, and that owner already appeared at an
     * earlier level - nobody remains who could ever satisfy that shared review, which would otherwise
     * block this level (and the whole chain) from ever completing no matter how many split reviews get
     * approved. With nobody left holding the normal track's responsibility, it imposes no requirement,
     * and completion depends purely on the split track - mirroring the split track's own established
     * "a SKIPPED assignment's splits are waived" rule, just applied to the normal track's assignments.
     */
    private boolean isEntireInstanceDone(ApprovalLevelInstance instance) {
        List<ApprovalAssignment> assignments = approvalAssignmentRepository.findByLevelInstance_InstanceId(instance.getInstanceId());

        boolean anyNormalTrackAssignmentSurvives = assignments.stream()
                .anyMatch(a -> a.getStatus() != AssignmentStatus.SKIPPED && a.getSplitReviews().isEmpty());
        if (anyNormalTrackAssignmentSurvives && !resolveStrategy(instance.getLevelType()).isLevelComplete(instance)) {
            return false;
        }

        return assignments.stream()
                .filter(a -> a.getStatus() != AssignmentStatus.SKIPPED)
                .filter(a -> !a.getSplitReviews().isEmpty())
                .allMatch(a -> a.getSplitReviews().stream()
                        .filter(r -> r.getSplit().getRemovedAt() == null)
                        .allMatch(r -> r.getStatus() == LineItemReviewStatus.APPROVED));
    }

    /** A split-owner assignment has just approved all of its own splits - mirrors completeLevelOrAdvanceSequential's tail for the normal track. */
    private void completeSplitOwnerAssignment(ExpenseReport report, ApprovalLevelInstance instance, ApprovalAssignment splitOwnerAssignment, int cycle) {
        splitOwnerAssignment.setStatus(AssignmentStatus.COMPLETED);
        approvalAssignmentRepository.save(splitOwnerAssignment);
        finishInstanceIfFullyDone(report, instance, cycle);
    }

    /**
     * Production-readiness audit, Parts 2-3: re-evaluates every split-owner assignment's own
     * reviews against the CURRENT live split state, and creates/extends assignments for any
     * newly-added split Cost Center. Runs once per {@code resumeInPlace} call, comparing against
     * this SAME cycle's original materialization - not against whatever the previous correction
     * looked like, so any number of successive corrections keep reconciling correctly.
     * <p>
     * A split whose Cost Center is unchanged but whose amount/mode changed is detected via {@code
     * ExpenseSplit.updatedAt} having moved past this instance's own {@code createdAt} - Hibernate's
     * dirty-checking only bumps {@code updatedAt} when a field actually changed (see {@code
     * ExpenseSplitServiceImpl}'s reconcile-in-place javadoc), so an untouched split's review is never
     * disturbed. Its review is reset to PENDING regardless of prior status - a stale APPROVED review
     * must never stand in for a materially different allocation (the locked example: Owner A approved
     * CC-A at 50%; after correction CC-A is 20% - that old approval cannot cover the new 20% figure).
     * A split whose Cost Center was removed from the set is never revisited here - {@code
     * isEntireInstanceDone} already excludes a removed split's review from the completion gate, the
     * same way it already excludes a SKIPPED assignment's splits.
     */
    private void reconcileSplitOwnerApprovals(ExpenseReport report, ApprovalLevelInstance instance) {
        List<ExpenseSplit> currentSplits = activeSplits(report);
        if (currentSplits.isEmpty()) {
            return;
        }

        var splitOwnerAssignments = approvalAssignmentRepository.findByLevelInstance_InstanceId(instance.getInstanceId()).stream()
                .filter(a -> !a.getSplitReviews().isEmpty())
                .toList();

        Set<UUID> coveredSplitIds = new java.util.HashSet<>();
        for (ApprovalAssignment assignment : splitOwnerAssignments) {
            boolean anyReactivated = false;
            for (ApprovalSplitReview review : assignment.getSplitReviews()) {
                coveredSplitIds.add(review.getSplit().getSplitId());
                boolean changedSinceMaterialization = review.getSplit().getUpdatedAt() != null
                        && review.getSplit().getUpdatedAt().isAfter(instance.getCreatedAt());
                if (changedSinceMaterialization && review.getStatus() != LineItemReviewStatus.PENDING) {
                    review.setStatus(LineItemReviewStatus.PENDING);
                    review.setComment(null);
                    review.setActedBy(null);
                    review.setActionedAt(null);
                    approvalSplitReviewRepository.save(review);
                    anyReactivated = true;
                }
            }
            // A stale review reset above may belong to an assignment that already finished (or was
            // superseded by an earlier cancellation pass) - it must become actionable again so the
            // owner can actually re-approve it.
            if (anyReactivated && assignment.getStatus() == AssignmentStatus.COMPLETED) {
                assignment.setStatus(AssignmentStatus.ACTIVE);
                approvalAssignmentRepository.save(assignment);
            }
        }

        // Any currently-active split with no existing review at all is newly added this correction.
        Map<String, List<ExpenseSplit>> newSplitsByOwner = new LinkedHashMap<>();
        for (ExpenseSplit split : currentSplits) {
            if (coveredSplitIds.contains(split.getSplitId())) {
                continue;
            }
            String ownerId = split.getCostCenter() != null ? split.getCostCenter().getOwnerEmployeeId() : null;
            if (ownerId == null || ownerId.isBlank()) {
                log.warn("New split {} (Cost Center {}) added during correction has no ownerEmployeeId configured - skipping its Cost Center Owner assignment",
                        split.getSplitId(), split.getCostCenter() != null ? split.getCostCenter().getCostCenterId() : null);
                continue;
            }
            newSplitsByOwner.computeIfAbsent(ownerId, k -> new ArrayList<>()).add(split);
        }

        for (Map.Entry<String, List<ExpenseSplit>> entry : newSplitsByOwner.entrySet()) {
            ApprovalAssignment assignment = splitOwnerAssignments.stream()
                    .filter(a -> a.getApproverId().equals(entry.getKey()))
                    .findFirst()
                    .orElse(null);
            if (assignment == null) {
                assignment = approvalAssignmentRepository.save(ApprovalAssignment.builder()
                        .levelInstance(instance)
                        .approverId(entry.getKey())
                        .sourceType(ApproverSourceType.COST_CENTER_OWNER)
                        .status(AssignmentStatus.PENDING)
                        .build());
                activateAssignment(assignment); // the level is already ACTIVE - a brand-new owner starts acting immediately (Phase 4's parallel-activation rule)
            } else if (assignment.getStatus() == AssignmentStatus.COMPLETED) {
                assignment.setStatus(AssignmentStatus.ACTIVE);
                approvalAssignmentRepository.save(assignment);
            }
            for (ExpenseSplit split : entry.getValue()) {
                ApprovalSplitReview review = approvalSplitReviewRepository.save(ApprovalSplitReview.builder()
                        .split(split)
                        .assignment(assignment)
                        .status(LineItemReviewStatus.PENDING)
                        .build());
                assignment.getSplitReviews().add(review);
            }
        }
    }

    /** Split-review counterpart of {@code LevelReviewStrategy.resumeCorrectedReviews} - resets only this instance's NEEDS_CORRECTION split reviews back to PENDING; a no-op for any instance with no split reviews at all. */
    private void resumeCorrectedSplitReviews(ApprovalLevelInstance instance) {
        approvalAssignmentRepository.findByLevelInstance_InstanceId(instance.getInstanceId()).stream()
                .flatMap(a -> a.getSplitReviews().stream())
                .filter(r -> r.getStatus() == LineItemReviewStatus.NEEDS_CORRECTION)
                .forEach(r -> {
                    r.setStatus(LineItemReviewStatus.PENDING);
                    approvalSplitReviewRepository.save(r);
                });
    }

    /** §11.1: Reimbursement Tracking only ever receives one, single, fully-approved whole report. */
    private void completeReport(ExpenseReport report) {
        report.setReportStatus(ReportStatus.APPROVED);
        report.setApprovedAt(LocalDateTime.now());
        applyPaymentRouting(report);
        expenseReportRepository.save(report);
        processCashAdvanceAdjustmentsForReport(report);
        approvalEventPublisher.publish("REPORT_APPROVED", report.getReportId(), "handoff=reimbursement-tracking");
        log.info("Report {} fully approved - handed off to Reimbursement Tracking", report.getReportId());
    }

    private void updateCashAdvanceStatusForReportSubmission(ExpenseReport report) {
        if (cashAdvanceAdjustmentRepository == null || cashAdvanceRepository == null || report == null) return;
        List<CashAdvanceAdjustment> adjustments = cashAdvanceAdjustmentRepository.findByReport_ReportId(report.getReportId());
        if (adjustments == null || adjustments.isEmpty()) return;
        for (CashAdvanceAdjustment adjustment : adjustments) {
            CashAdvance advance = adjustment.getCashAdvance();
            if (advance != null) {
                String st = advance.getStatus() != null ? advance.getStatus().toUpperCase() : "";
                if (List.of("DISBURSED", "IN_PROGRESS", "IN PROGRESS", "RECONCILIATION_PENDING", "RECONCILIATION PENDING").contains(st)) {
                    advance.setStatus("SUBMITTED_FOR_REVIEW");
                    cashAdvanceRepository.save(advance);
                }
            }
        }
    }

    private void updateCashAdvanceStatusForReportReview(ExpenseReport report) {
        if (cashAdvanceAdjustmentRepository == null || cashAdvanceRepository == null || report == null) return;
        List<CashAdvanceAdjustment> adjustments = cashAdvanceAdjustmentRepository.findByReport_ReportId(report.getReportId());
        if (adjustments == null || adjustments.isEmpty()) return;
        for (CashAdvanceAdjustment adjustment : adjustments) {
            CashAdvance advance = adjustment.getCashAdvance();
            if (advance != null) {
                String st = advance.getStatus() != null ? advance.getStatus().toUpperCase() : "";
                if (List.of("DISBURSED", "IN_PROGRESS", "IN PROGRESS", "RECONCILIATION_PENDING", "RECONCILIATION PENDING", "SUBMITTED_FOR_REVIEW", "SUBMITTED FOR REVIEW").contains(st)) {
                    advance.setStatus("UNDER_REVIEW");
                    cashAdvanceRepository.save(advance);
                }
            }
        }
    }

    private void processCashAdvanceAdjustmentsForReport(ExpenseReport report) {
        if (cashAdvanceAdjustmentRepository == null || cashAdvanceRepository == null) return;
        List<CashAdvanceAdjustment> adjustments = cashAdvanceAdjustmentRepository.findByReport_ReportId(report.getReportId());
        if (adjustments == null || adjustments.isEmpty()) return;

        BigDecimal verifiedAmount = report.getReimbursableAmount() != null ? report.getReimbursableAmount() : report.getTotalAmount();

        for (CashAdvanceAdjustment adjustment : adjustments) {
            CashAdvance advance = adjustment.getCashAdvance();
            if (advance == null) continue;

            if (verifiedAmount != null) {
                adjustment.setAdjustedAmount(verifiedAmount);
                cashAdvanceAdjustmentRepository.save(adjustment);
            }

            List<CashAdvanceAdjustment> allAdjustments = cashAdvanceAdjustmentRepository.findByCashAdvance_AdvanceId(advance.getAdvanceId());
            BigDecimal totalAdjusted = allAdjustments.stream()
                    .map(a -> a.getAdjustedAmount() != null ? a.getAdjustedAmount() : java.math.BigDecimal.ZERO)
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

            BigDecimal totalRepaid = advance.getRepayments() != null 
                    ? advance.getRepayments().stream().map(r -> r.getAmount() != null ? r.getAmount() : java.math.BigDecimal.ZERO).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                    : java.math.BigDecimal.ZERO;

            BigDecimal newBalance = advance.getAmount().subtract(totalAdjusted).subtract(totalRepaid);
            advance.setOutstandingBalance(newBalance);
            if (newBalance.compareTo(java.math.BigDecimal.ZERO) == 0) {
                advance.setStatus("CLOSED");
            } else {
                advance.setStatus("SETTLEMENT_PENDING");
            }
            cashAdvanceRepository.save(advance);
            approvalEventPublisher.publish("CASH_ADVANCE_ADJUSTED", advance.getAdvanceId(), "reportId=" + report.getReportId() + " amount=" + verifiedAmount);
        }
    }

    /**
     * Downstream payment/invoice routing (Finance Verification Phase 6) - deliberately independent
     * of {@code reportStatus} (Rule #8: never mix downstream payment state into workflow state).
     * A no-op (leaves {@code paymentRoutingStatus = NONE}) for any chain with no Finance level at
     * all, so existing Manager-only flows are completely unaffected. Only ever runs once, at the
     * same moment the report reaches APPROVED - there is no separate "Finance verified but payment
     * routing pending" state, since nothing in this codebase can complete a chain without also
     * completing its last level.
     */
    private void applyPaymentRouting(ExpenseReport report) {
        int cycle = currentSubmissionCycle(report.getReportId());
        boolean hadFinanceLevel = approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(report.getReportId(), cycle)
                .stream()
                .anyMatch(instance -> instance.getLevelType() == LevelType.FINANCE_VERIFICATION);
        if (!hadFinanceLevel) {
            return;
        }
        // Idempotency guard (Finance Verification Phase 8): applyPaymentRouting/completeReport can
        // only reach here once per report under normal state-machine flow, but a concurrent
        // near-simultaneous verify on the last two line items could both observe "level complete"
        // before either commits - ExpenseReport.version already forces one of those two racing
        // transactions to fail at commit, and this explicit check makes the second,
        // already-rolled-forward case a clean no-op instead of relying on that alone.
        if (report.getPaymentRoutingStatus() != PaymentRoutingStatus.NONE) {
            return;
        }

        // Budget is NOT consumed here - only once payment actually completes (AP Payment Phase 2:
        // ApPaymentServiceImpl.markPaymentCompleted). Reaching APPROVED_FOR_PAYMENT/INVOICE_HANDOFF_PENDING
        // is a routing decision, not a financial commitment yet.
        boolean anyClientBillable = report.getExpenseLineItems() != null && report.getExpenseLineItems().stream()
                .anyMatch(lineItem -> Boolean.TRUE.equals(lineItem.getClientBillable()));

        if (anyClientBillable) {
            report.setPaymentRoutingStatus(PaymentRoutingStatus.INVOICE_HANDOFF_PENDING);
            approvalEventPublisher.publish("REPORT_INVOICE_HANDOFF", report.getReportId(), "reason=client-billable");
        } else {
            report.setPaymentRoutingStatus(PaymentRoutingStatus.APPROVED_FOR_PAYMENT);
            approvalEventPublisher.publish("REPORT_APPROVED_FOR_PAYMENT", report.getReportId(), "reason=internal");
        }
    }

    /**
     * Releases the cycle's ACTIVE budget encumbrances (Phase 5) BEFORE touching any level instance -
     * this single choke point is reached by recall, cancel, rejectReport, and fullRestart's old-cycle
     * teardown, so none of those call sites need their own release call. A no-op if nothing is ACTIVE
     * for this report/cycle (e.g. a report that was never actually submitted, or normal reports
     * predating Phase 3/5's budget wiring).
     */
    private void cancelAllOpenInstances(ExpenseReport report, int cycle) {
        budgetEncumbranceService.releaseActiveForCycle(report.getReportId(), cycle);
        approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(report.getReportId(), cycle)
                .forEach(instance -> {
                    if (instance.getStatus() != LevelInstanceStatus.COMPLETED) {
                        instance.setStatus(LevelInstanceStatus.CANCELLED);
                        approvalLevelInstanceRepository.save(instance);
                        // Close every still-open assignment on this instance too - under ANY_OF/ALL_OF
                        // quorum more than one co-approver can be ACTIVE at once, and unlike the normal
                        // completion path (completeLevelOrAdvanceSequential), reject/recall/cancel never
                        // otherwise touches sibling assignments. Left ACTIVE, a co-approver's "My Queue"
                        // (assignment-status based, not report-status based) would keep showing a report
                        // whose approval workflow has already terminated.
                        approvalAssignmentRepository.findByLevelInstance_InstanceId(instance.getInstanceId()).stream()
                                .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE || a.getStatus() == AssignmentStatus.PENDING)
                                .forEach(a -> {
                                    a.setStatus(AssignmentStatus.SUPERSEDED);
                                    approvalAssignmentRepository.save(a);
                                });
                    }
                });
    }

    // ---------------------------------------------------------------------
    // Guards & helpers
    // ---------------------------------------------------------------------

    /** Warnings never block submission (Decision 6/Phase 3's "warn only" rule) - just surfaced to the log for now; Phase 7 owns exposing them on the response itself. */
    private void logBudgetWarnings(BudgetEncumbranceOutcome outcome) {
        if (outcome == null) {
            return;
        }
        for (BudgetWarning warning : outcome.warnings()) {
            log.warn("Cost center {} Effective Available Budget fell below its warning threshold ({}) after this submission's encumbrance: now {}",
                    warning.costCenterCode(), warning.warningThreshold(), warning.effectiveAvailableAfterEncumbrance());
        }
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
        return statusOk && !blocksRecall(report);
    }

    /**
     * Recall's restriction (§6), extended for Finance Verification (spec §40): blocked once any
     * level has completed (unchanged), OR once a FINANCE_VERIFICATION level has ever gone ACTIVE in
     * the current cycle - "managerial/business justification has already progressed into financial
     * verification," so recall should not be able to undo that even while the report is currently
     * AWAITING_CORRECTION from a Finance query. Cancel is deliberately NOT subject to this extra
     * check (spec §40: cancel remains a permitted withdrawal) - see {@link #hasAnyLevelApproved}.
     */
    private boolean blocksRecall(ExpenseReport report) {
        if (report.getReportStatus() == ReportStatus.DRAFT) {
            return false;
        }
        int cycle = currentSubmissionCycle(report.getReportId());
        return approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(report.getReportId(), cycle)
                .stream()
                .anyMatch(i -> i.getStatus() == LevelInstanceStatus.COMPLETED
                        || (i.getLevelType() == LevelType.FINANCE_VERIFICATION && i.getStatus() == LevelInstanceStatus.ACTIVE));
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
        return expenseReportResponseFactory.toResponse(report);
    }
}
