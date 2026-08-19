package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.dto.response.FinanceLineItemReviewResponse;
import com.expense_management_service.dto.response.FinancePendingLineItemResponse;
import com.expense_management_service.dto.response.FinanceQueueItemResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.entity.ApprovalAssignment;
import com.expense_management_service.entity.ApprovalLevelInstance;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.FinanceVerificationReview;
import com.expense_management_service.entity.GlAccount;
import com.expense_management_service.enums.AssignmentStatus;
import com.expense_management_service.enums.FinanceVerificationStatus;
import com.expense_management_service.enums.LevelInstanceStatus;
import com.expense_management_service.enums.LevelType;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.repository.ApprovalAssignmentRepository;
import com.expense_management_service.repository.ApprovalLevelInstanceRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.entity.VerificationQuery;
import com.expense_management_service.mapper.ApprovalFlowMapper;
import com.expense_management_service.repository.FinanceVerificationReviewRepository;
import com.expense_management_service.repository.VerificationQueryRepository;
import com.expense_management_service.service.ApprovalEventPublisher;
import com.expense_management_service.service.ApprovalWorkflowService;
import com.expense_management_service.service.DelegationService;
import com.expense_management_service.service.FinanceEligibilityResult;
import com.expense_management_service.service.FinanceVerificationEligibilityChecker;
import com.expense_management_service.service.FinanceVerificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class FinanceVerificationServiceImpl implements FinanceVerificationService {

    private final ExpenseReportRepository expenseReportRepository;
    private final ApprovalLevelInstanceRepository approvalLevelInstanceRepository;
    private final ApprovalAssignmentRepository approvalAssignmentRepository;
    private final FinanceVerificationReviewRepository financeVerificationReviewRepository;
    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final DelegationService delegationService;
    private final FinanceVerificationEligibilityChecker financeVerificationEligibilityChecker;
    private final ApprovalEventPublisher approvalEventPublisher;
    private final ApprovalWorkflowService approvalWorkflowService;
    private final ExpenseReportResponseFactory expenseReportResponseFactory;
    private final VerificationQueryRepository verificationQueryRepository;

    @Override
    public ExpenseReportResponse verifyLineItem(UUID reportId, UUID lineItemId, String actingEmployeeId) {
        FinanceActionContext ctx = resolveContext(reportId, lineItemId, actingEmployeeId);

        FinanceEligibilityResult eligibility = financeVerificationEligibilityChecker.check(ctx.lineItem());
        if (!eligibility.eligible()) {
            throw new IllegalArgumentException("Cannot verify line item. Reason: " + eligibility.reason());
        }

        GlAccount glAccount = ctx.lineItem().getCategory().getGlAccount();
        FinanceVerificationReview review = ctx.review();
        review.setGlAccountIdAtVerification(glAccount.getGlAccountId());
        review.setGlAccountCodeSnapshot(glAccount.getGlAccountCode());
        review.setPolicyExceptionResolvedFlag(true);
        review.setReceiptValidatedFlag(true);
        review.setStatus(FinanceVerificationStatus.VERIFIED);
        review.setActedBy(actingEmployeeId.equals(ctx.authorizing().getApproverId()) ? null : actingEmployeeId);
        review.setActionedAt(LocalDateTime.now());
        financeVerificationReviewRepository.save(review);
        approvalEventPublisher.publish("LINE_ITEM_VERIFIED", reportId, "lineItem=" + lineItemId + " by=" + actingEmployeeId);

        approvalWorkflowService.advanceAfterLevelReviewed(reportId, ctx.activeInstance().getInstanceId(), ctx.authorizing().getApproverId());

        log.info("Line item {} verified on report {} by {}", lineItemId, reportId, actingEmployeeId);
        return expenseReportResponseFactory.toResponse(findReport(reportId));
    }

    @Override
    public ExpenseReportResponse queryLineItem(UUID reportId, UUID lineItemId, String actingEmployeeId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to raise a Finance query");
        }
        FinanceActionContext ctx = resolveContext(reportId, lineItemId, actingEmployeeId);

        verificationQueryRepository.save(VerificationQuery.builder()
                .lineItem(ctx.lineItem())
                .levelInstance(ctx.activeInstance())
                .raisedBy(actingEmployeeId)
                .queryText(reason)
                .status(FinanceVerificationStrategy.QUERY_STATUS_RAISED)
                .raisedAt(LocalDateTime.now())
                .build());

        FinanceVerificationReview review = ctx.review();
        review.setStatus(FinanceVerificationStatus.QUERIED);
        review.setComment(reason);
        review.setActedBy(actingEmployeeId.equals(ctx.authorizing().getApproverId()) ? null : actingEmployeeId);
        review.setActionedAt(LocalDateTime.now());
        financeVerificationReviewRepository.save(review);

        ctx.report().setReportStatus(ReportStatus.AWAITING_CORRECTION);
        expenseReportRepository.save(ctx.report());
        approvalEventPublisher.publish("VERIFICATION_QUERY_RAISED", reportId, "lineItem=" + lineItemId + " by=" + actingEmployeeId);

        log.info("Finance query raised on line item {} of report {} by {}: {}", lineItemId, reportId, actingEmployeeId, reason);
        return expenseReportResponseFactory.toResponse(findReport(reportId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<FinanceQueueItemResponse> getFinanceQueue(String actingEmployeeId, Pageable pageable) {
        Set<String> approverIds = delegationService.resolveApproverIdsActingFor(actingEmployeeId);

        Page<UUID> reportIdsPage = approvalAssignmentRepository.findDistinctReportIdsByStatusAndApproverIdInAndLevelType(
                AssignmentStatus.ACTIVE, approverIds, LevelType.FINANCE_VERIFICATION, pageable);

        List<FinanceQueueItemResponse> items = reportIdsPage.getContent().stream()
                .map(reportId -> representativeFinanceAssignment(reportId, approverIds))
                .flatMap(Optional::stream)
                .map(this::toQueueItem)
                .toList();

        return PageResponse.of(new PageImpl<>(items, pageable, reportIdsPage.getTotalElements()));
    }

    private Optional<ApprovalAssignment> representativeFinanceAssignment(UUID reportId, Set<String> approverIds) {
        return approvalAssignmentRepository.findByLevelInstance_Report_ReportId(reportId).stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE)
                .filter(a -> a.getLevelInstance().getLevelType() == LevelType.FINANCE_VERIFICATION)
                .filter(a -> approverIds.contains(a.getApproverId()))
                .findFirst();
    }

    private FinanceQueueItemResponse toQueueItem(ApprovalAssignment assignment) {
        ApprovalLevelInstance instance = assignment.getLevelInstance();
        ExpenseReport report = instance.getReport();

        var pendingReviews = financeVerificationReviewRepository
                .findByLevelInstance_InstanceIdAndStatus(instance.getInstanceId(), FinanceVerificationStatus.PENDING);

        List<FinancePendingLineItemResponse> pendingLineItems = pendingReviews.stream()
                .map(review -> {
                    ExpenseLineItem lineItem = review.getLineItem();
                    FinanceEligibilityResult eligibility = financeVerificationEligibilityChecker.check(lineItem);
                    GlAccount glAccount = lineItem.getCategory() != null ? lineItem.getCategory().getGlAccount() : null;
                    return new FinancePendingLineItemResponse(
                            lineItem.getLineItemId(), review.getReviewId(),
                            lineItem.getCategory() != null ? lineItem.getCategory().getCategoryName() : null,
                            lineItem.getMerchantName(), lineItem.getDescription(), lineItem.getExpenseDate(),
                            lineItem.getAmount(), lineItem.getCurrency() != null ? lineItem.getCurrency().getCurrencyCode() : null,
                            glAccount != null ? glAccount.getGlAccountCode() : null,
                            eligibility.eligible(), eligibility.reason(), Boolean.TRUE.equals(lineItem.getClientBillable()));
                })
                .toList();

        return new FinanceQueueItemResponse(
                report.getReportId(), report.getReportNumber(), report.getEmployeeId(), report.getTotalAmount(),
                report.getCurrency() != null ? report.getCurrency().getCurrencyCode() : null,
                report.getCostCenter() != null ? report.getCostCenter().getCostCenterName() : null,
                instance.getLevelOrder(), pendingLineItems);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FinanceLineItemReviewResponse> getFinanceReviews(UUID reportId, String actingEmployeeId) {
        ExpenseReport report = findReport(reportId);
        assertCanViewFinanceReviews(report, actingEmployeeId);

        int cycle = currentCycle(reportId);
        var financeInstances = approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(reportId, cycle).stream()
                .filter(instance -> instance.getLevelType() == LevelType.FINANCE_VERIFICATION)
                .toList();

        List<FinanceLineItemReviewResponse> result = new ArrayList<>();
        for (ApprovalLevelInstance instance : financeInstances) {
            for (FinanceVerificationReview review : financeVerificationReviewRepository.findByLevelInstance_InstanceId(instance.getInstanceId())) {
                result.add(new FinanceLineItemReviewResponse(
                        review.getLineItem().getLineItemId(), review.getReviewId(), review.getStatus(), review.getComment(),
                        review.getActedBy(), review.getActionedAt(), review.getGlAccountCodeSnapshot(),
                        review.getPolicyExceptionResolvedFlag(), review.getReceiptValidatedFlag(),
                        instance.getLevelOrder(), instance.getLevelName(),
                        ApprovalFlowMapper.resolveDisplayName(instance.getLevelName(), instance.getLevelOrder())));
            }
        }
        return result;
    }

    private void assertCanViewFinanceReviews(ExpenseReport report, String actingEmployeeId) {
        if (Objects.equals(report.getEmployeeId(), actingEmployeeId)) {
            return;
        }
        boolean everAssigned = approvalAssignmentRepository.findByLevelInstance_Report_ReportId(report.getReportId())
                .stream()
                .anyMatch(a -> delegationService.canAct(actingEmployeeId, a.getApproverId()));
        if (!everAssigned) {
            throw new AccessDeniedException("You may not view this report's Finance verification history");
        }
    }

    /** Shared lookup + authorization + idempotency guard for both VERIFY and QUERY - kept as one place so the two actions can never authorize differently. */
    private FinanceActionContext resolveContext(UUID reportId, UUID lineItemId, String actingEmployeeId) {
        ExpenseReport report = findReport(reportId);
        if (report.getReportStatus() != ReportStatus.PENDING_FINANCE_VERIFICATION) {
            throw new IllegalArgumentException(
                    "Report " + reportId + " has no Finance Verification level currently active");
        }

        ApprovalLevelInstance activeInstance = approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, currentCycle(reportId), LevelInstanceStatus.ACTIVE)
                .filter(instance -> instance.getLevelType() == LevelType.FINANCE_VERIFICATION)
                .orElseThrow(() -> new IllegalStateException(
                        "Report " + reportId + " is PENDING_FINANCE_VERIFICATION but has no ACTIVE Finance level instance"));

        ApprovalAssignment authorizing = approvalAssignmentRepository.findByLevelInstance_InstanceId(activeInstance.getInstanceId())
                .stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE)
                .filter(a -> delegationService.canAct(actingEmployeeId, a.getApproverId()))
                .findFirst()
                .orElseThrow(() -> new AccessDeniedException(
                        "You are not an active Finance approver (or delegate) for this report's current level"));

        ExpenseLineItem lineItem = expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Line item " + lineItemId + " is not part of report " + reportId));

        FinanceVerificationReview review = financeVerificationReviewRepository
                .findByLineItem_LineItemIdAndLevelInstance_InstanceId(lineItemId, activeInstance.getInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No pending Finance review for line item " + lineItemId + " at this level"));
        if (review.getStatus() != FinanceVerificationStatus.PENDING) {
            throw new IllegalArgumentException("This line item has already been reviewed at this level: " + review.getStatus());
        }

        return new FinanceActionContext(report, activeInstance, authorizing, lineItem, review);
    }

    private record FinanceActionContext(ExpenseReport report, ApprovalLevelInstance activeInstance,
                                         ApprovalAssignment authorizing, ExpenseLineItem lineItem, FinanceVerificationReview review) {
    }

    private int currentCycle(UUID reportId) {
        return approvalLevelInstanceRepository.findMaxSubmissionCycle(reportId);
    }

    private ExpenseReport findReport(UUID reportId) {
        return expenseReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseReport not found with id: " + reportId));
    }
}
