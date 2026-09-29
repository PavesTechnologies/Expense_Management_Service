package com.expense_management_service.service.impl;

import com.expense_management_service.dto.request.LineItemReviewRequest;
import com.expense_management_service.dto.request.RejectReportRequest;
import com.expense_management_service.dto.response.ExpenseReportResponse;
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
import com.expense_management_service.entity.FinanceVerificationReview;
import com.expense_management_service.enums.ApproverSourceType;
import com.expense_management_service.enums.AssignmentStatus;
import com.expense_management_service.enums.FinanceVerificationStatus;
import com.expense_management_service.enums.LevelInstanceStatus;
import com.expense_management_service.enums.LevelQuorum;
import com.expense_management_service.enums.LevelType;
import com.expense_management_service.enums.LineItemReviewStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.enums.SplitType;
import com.expense_management_service.mapper.ExpenseReportMapper;
import com.expense_management_service.repository.ApprovalAssignmentRepository;
import com.expense_management_service.repository.ApprovalLevelInstanceRepository;
import com.expense_management_service.repository.ApprovalLineItemReviewRepository;
import com.expense_management_service.repository.ApprovalSplitReviewRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.PolicyViolationRepository;
import com.expense_management_service.service.ApprovalEventPublisher;
import com.expense_management_service.service.ApprovalFlowResolutionService;
import com.expense_management_service.service.ApproverSourceResolver;
import com.expense_management_service.service.ChainCorrectnessService;
import com.expense_management_service.service.DelegationService;
import com.expense_management_service.service.PolicyDecision;
import com.expense_management_service.service.PolicyEvaluationGateway;
import com.expense_management_service.service.SlaPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the core state-machine transitions. Uses a single-level, single-approver, single-line-item
 * flow as the default fixture, built up progressively rather than as a giant shared setup, so each
 * test's actual repository interactions stay traceable.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class ApprovalWorkflowServiceImplTest {

    @Mock private ExpenseReportRepository expenseReportRepository;
    @Mock private ApprovalLevelInstanceRepository approvalLevelInstanceRepository;
    @Mock private ApprovalAssignmentRepository approvalAssignmentRepository;
    @Mock private ApprovalLineItemReviewRepository approvalLineItemReviewRepository;
    @Mock private ApprovalSplitReviewRepository approvalSplitReviewRepository;
    @Mock private PolicyViolationRepository policyViolationRepository;
    @Mock private ApprovalFlowResolutionService approvalFlowResolutionService;
    @Mock private ApproverSourceResolver approverSourceResolver;
    @Mock private ChainCorrectnessService chainCorrectnessService;
    @Mock private com.expense_management_service.service.BudgetEncumbranceService budgetEncumbranceService;
    @Mock private DelegationService delegationService;
    @Mock private PolicyEvaluationGateway policyEvaluationGateway;
    @Mock private ApprovalEventPublisher approvalEventPublisher;
    @Mock private SlaPolicyService slaPolicyService;
    @Mock private com.expense_management_service.service.MaterialChangeEvaluator materialChangeEvaluator;
    @Mock private com.expense_management_service.repository.FinanceVerificationReviewRepository financeVerificationReviewRepository;
    @Mock private com.expense_management_service.repository.VerificationQueryRepository verificationQueryRepository;

    private ApprovalWorkflowServiceImpl service;

    private final UUID reportId = UUID.randomUUID();
    private final UUID flowId = UUID.randomUUID();
    private final UUID lineItemId = UUID.randomUUID();
    private final String submitterId = "5100001";
    private final String approverId = "5100002";

    private final List<ApprovalLevelInstance> savedInstances = new ArrayList<>();
    private final List<ApprovalAssignment> savedAssignments = new ArrayList<>();
    private final List<ApprovalLineItemReview> savedReviews = new ArrayList<>();
    private final List<ApprovalSplitReview> savedSplitReviews = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ApprovalWorkflowServiceImpl(expenseReportRepository, approvalLevelInstanceRepository,
                approvalAssignmentRepository, approvalLineItemReviewRepository, approvalSplitReviewRepository, policyViolationRepository,
                approvalFlowResolutionService, approverSourceResolver, chainCorrectnessService, budgetEncumbranceService, delegationService,
                policyEvaluationGateway, approvalEventPublisher, slaPolicyService,
                new com.expense_management_service.mapper.PolicyViolationMapper(),
                List.of(new ApprovalReviewStrategy(approvalLineItemReviewRepository)),
                new ExpenseReportResponseFactory(new ExpenseReportMapper(), policyViolationRepository),
                materialChangeEvaluator);

        when(materialChangeEvaluator.computeGlAccountFingerprint(any())).thenReturn("");
        when(policyEvaluationGateway.evaluate(any())).thenReturn(new PolicyDecision(true, List.of()));
        when(policyViolationRepository.findByLineItem_Report_ReportId(any())).thenReturn(List.of());
        when(slaPolicyService.resolveSlaBusinessDays()).thenReturn(3);
        when(delegationService.canAct(any(), any())).thenAnswer(inv -> inv.getArgument(0).equals(inv.getArgument(1)));
        when(budgetEncumbranceService.validateAndEncumber(any(), anyInt()))
                .thenReturn(new com.expense_management_service.dto.response.BudgetEncumbranceOutcome(List.of(), List.of()));

        when(approvalLevelInstanceRepository.save(any(ApprovalLevelInstance.class))).thenAnswer(inv -> {
            ApprovalLevelInstance i = inv.getArgument(0);
            if (i.getInstanceId() == null) {
                i.setInstanceId(UUID.randomUUID());
            }
            if (i.getCreatedAt() == null) {
                i.setCreatedAt(java.time.LocalDateTime.now()); // simulates @CreationTimestamp - resumeInPlace's reconciliation reads this
            }
            savedInstances.removeIf(existing -> existing.getInstanceId().equals(i.getInstanceId()));
            savedInstances.add(i);
            return i;
        });
        when(approvalAssignmentRepository.save(any(ApprovalAssignment.class))).thenAnswer(inv -> {
            ApprovalAssignment a = inv.getArgument(0);
            if (a.getAssignmentId() == null) {
                a.setAssignmentId(UUID.randomUUID());
            }
            savedAssignments.removeIf(existing -> existing.getAssignmentId().equals(a.getAssignmentId()));
            savedAssignments.add(a);
            return a;
        });
        when(approvalLineItemReviewRepository.save(any(ApprovalLineItemReview.class))).thenAnswer(inv -> {
            ApprovalLineItemReview r = inv.getArgument(0);
            if (r.getReviewId() == null) {
                r.setReviewId(UUID.randomUUID());
            }
            savedReviews.removeIf(existing -> existing.getReviewId().equals(r.getReviewId()));
            savedReviews.add(r);
            return r;
        });
        when(expenseReportRepository.save(any(ExpenseReport.class))).thenAnswer(inv -> inv.getArgument(0));

        when(approvalAssignmentRepository.findByLevelInstance_InstanceId(any()))
                .thenAnswer(inv -> savedAssignments.stream()
                        .filter(a -> a.getLevelInstance().getInstanceId().equals(inv.getArgument(0))).toList());
        when(approvalAssignmentRepository.findByLevelInstance_Report_ReportId(any()))
                .thenAnswer(inv -> savedAssignments.stream()
                        .filter(a -> a.getLevelInstance().getReport().getReportId().equals(inv.getArgument(0))).toList());
        when(delegationService.resolveApproverIdsActingFor(any()))
                .thenAnswer(inv -> Set.of((String) inv.getArgument(0)));
        when(approvalAssignmentRepository.findDistinctReportIdsByStatusAndApproverIdIn(any(), any(), any()))
                .thenAnswer(inv -> {
                    AssignmentStatus status = inv.getArgument(0);
                    @SuppressWarnings("unchecked")
                    java.util.Collection<String> approverIds = inv.getArgument(1);
                    Pageable pageable = inv.getArgument(2);
                    List<UUID> reportIds = savedAssignments.stream()
                            .filter(a -> a.getStatus() == status)
                            .filter(a -> approverIds.contains(a.getApproverId()))
                            .map(a -> a.getLevelInstance().getReport().getReportId())
                            .distinct()
                            .toList();
                    return new PageImpl<>(reportIds, pageable, reportIds.size());
                });
        when(expenseReportRepository.findHistoryForApprover(any(), anyBoolean(), anyBoolean(), any())).thenAnswer(inv -> {
            String employeeId = inv.getArgument(0);
            boolean includeApproved = inv.getArgument(1);
            boolean includeRejected = inv.getArgument(2);
            Pageable pageable = inv.getArgument(3);
            // Reuses whatever findById(reportId) currently returns - this test only ever models one report.
            List<ExpenseReport> content = expenseReportRepository.findById(reportId)
                    .filter(r -> (includeApproved && r.getReportStatus() == ReportStatus.APPROVED
                                    && savedAssignments.stream().anyMatch(a -> a.getApproverId().equals(employeeId) && a.getStatus() == AssignmentStatus.COMPLETED))
                            || (includeRejected && employeeId.equals(r.getRejectedBy())))
                    .map(List::of)
                    .orElse(List.of());
            return new PageImpl<>(content, pageable, content.size());
        });
        when(approvalLineItemReviewRepository.findByLevelInstance_InstanceId(any()))
                .thenAnswer(inv -> savedReviews.stream()
                        .filter(r -> r.getLevelInstance().getInstanceId().equals(inv.getArgument(0))).toList());
        when(approvalLineItemReviewRepository.findByLevelInstance_InstanceIdAndStatus(any(), any()))
                .thenAnswer(inv -> savedReviews.stream()
                        .filter(r -> r.getLevelInstance().getInstanceId().equals(inv.getArgument(0)))
                        .filter(r -> r.getStatus() == inv.getArgument(1)).toList());
        when(approvalLineItemReviewRepository.findByLineItem_LineItemIdAndLevelInstance_InstanceId(any(), any()))
                .thenAnswer(inv -> savedReviews.stream()
                        .filter(r -> r.getLineItem().getLineItemId().equals(inv.getArgument(0)))
                        .filter(r -> r.getLevelInstance().getInstanceId().equals(inv.getArgument(1)))
                        .findFirst());
        when(approvalLevelInstanceRepository.findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(eq(reportId), anyInt()))
                .thenAnswer(inv -> savedInstances.stream()
                        .filter(i -> i.getSubmissionCycle().equals(inv.getArgument(1)))
                        .sorted(java.util.Comparator.comparing(ApprovalLevelInstance::getLevelOrder)).toList());
        when(approvalLevelInstanceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus(eq(reportId), anyInt(), any()))
                .thenAnswer(inv -> savedInstances.stream()
                        .filter(i -> i.getSubmissionCycle().equals(inv.getArgument(1)))
                        .filter(i -> i.getStatus() == inv.getArgument(2))
                        .findFirst());
        when(approvalLevelInstanceRepository.findMaxSubmissionCycle(reportId))
                .thenAnswer(inv -> savedInstances.stream().mapToInt(ApprovalLevelInstance::getSubmissionCycle).max().orElse(0));
        when(approvalLevelInstanceRepository.findById(any()))
                .thenAnswer(inv -> savedInstances.stream().filter(i -> i.getInstanceId().equals(inv.getArgument(0))).findFirst());

        when(approvalSplitReviewRepository.save(any(ApprovalSplitReview.class))).thenAnswer(inv -> {
            ApprovalSplitReview r = inv.getArgument(0);
            if (r.getReviewId() == null) {
                r.setReviewId(UUID.randomUUID());
            }
            savedSplitReviews.removeIf(existing -> existing.getReviewId().equals(r.getReviewId()));
            savedSplitReviews.add(r);
            return r;
        });
        when(approvalSplitReviewRepository.findByAssignment_AssignmentId(any()))
                .thenAnswer(inv -> savedSplitReviews.stream()
                        .filter(r -> r.getAssignment().getAssignmentId().equals(inv.getArgument(0))).toList());
        when(approvalSplitReviewRepository.findBySplit_SplitIdAndAssignment_AssignmentId(any(), any()))
                .thenAnswer(inv -> savedSplitReviews.stream()
                        .filter(r -> r.getSplit().getSplitId().equals(inv.getArgument(0)))
                        .filter(r -> r.getAssignment().getAssignmentId().equals(inv.getArgument(1)))
                        .findFirst());
    }

    private ExpenseLineItem lineItem() {
        return ExpenseLineItem.builder().lineItemId(lineItemId).amount(new java.math.BigDecimal("1000")).build();
    }

    private ExpenseReport draftReport() {
        return ExpenseReport.builder().reportId(reportId).employeeId(submitterId)
                .reportStatus(ReportStatus.DRAFT)
                .costCenter(com.expense_management_service.entity.CostCenter.builder().costCenterId(UUID.randomUUID()).build())
                .expenseLineItems(List.of(lineItem()))
                .build();
    }

    /** One level, SEQUENTIAL quorum, one NAMED_USER approver entry. */
    private ApprovalFlow singleLevelFlow() {
        ApprovalFlow flow = ApprovalFlow.builder().flowId(flowId).name("Single level").isCatchAll(false).build();
        ApprovalLevel level = ApprovalLevel.builder().levelId(UUID.randomUUID()).flow(flow).levelOrder(1).quorum(LevelQuorum.SEQUENTIAL).build();
        ApprovalLevelApprover entry = ApprovalLevelApprover.builder().entryId(UUID.randomUUID()).level(level)
                .entryOrder(1).sourceType(ApproverSourceType.NAMED_USER).sourceReference(approverId).build();
        level.getApprovers().add(entry);
        flow.getLevels().add(level);
        return flow;
    }

    @Test
    void submit_materializesChainAndActivatesFirstLevel() {
        ExpenseReport report = draftReport();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(singleLevelFlow());
        when(approverSourceResolver.resolve(any(), any())).thenReturn(Optional.of(approverId));

        ExpenseReportResponse response = service.submit(reportId);

        assertThat(response.reportStatus()).isEqualTo(ReportStatus.PENDING_APPROVAL.name());
        assertThat(savedInstances).hasSize(1);
        assertThat(savedInstances.get(0).getStatus()).isEqualTo(LevelInstanceStatus.ACTIVE);
        assertThat(savedAssignments).hasSize(1);
        assertThat(savedAssignments.get(0).getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(savedAssignments.get(0).getApproverId()).isEqualTo(approverId);
        assertThat(savedReviews).hasSize(1);
        assertThat(savedReviews.get(0).getStatus()).isEqualTo(LineItemReviewStatus.PENDING);
        verify(chainCorrectnessService).applyCorrectnessPasses(report, 1);
    }

    /** Manager (APPROVAL) then Finance (FINANCE_VERIFICATION) - regression for the levelType-snapshot fix (§Phase 4). */
    private ApprovalFlow managerThenFinanceFlow() {
        ApprovalFlow flow = ApprovalFlow.builder().flowId(flowId).name("Manager then Finance").isCatchAll(false).build();
        ApprovalLevel managerLevel = ApprovalLevel.builder().levelId(UUID.randomUUID()).flow(flow).levelOrder(1)
                .quorum(LevelQuorum.SEQUENTIAL).levelType(com.expense_management_service.enums.LevelType.APPROVAL).build();
        managerLevel.getApprovers().add(ApprovalLevelApprover.builder().entryId(UUID.randomUUID()).level(managerLevel)
                .entryOrder(1).sourceType(ApproverSourceType.NAMED_USER).sourceReference(approverId).build());
        ApprovalLevel financeLevel = ApprovalLevel.builder().levelId(UUID.randomUUID()).flow(flow).levelOrder(2)
                .quorum(LevelQuorum.SEQUENTIAL).levelType(com.expense_management_service.enums.LevelType.FINANCE_VERIFICATION).build();
        financeLevel.getApprovers().add(ApprovalLevelApprover.builder().entryId(UUID.randomUUID()).level(financeLevel)
                .entryOrder(1).sourceType(ApproverSourceType.NAMED_USER).sourceReference("5100050").build());
        flow.getLevels().add(managerLevel);
        flow.getLevels().add(financeLevel);
        return flow;
    }

    /** Single FINANCE_VERIFICATION level, NAMED_USER approver - a "Finance-only flow" per spec test #4. */
    private ApprovalFlow financeOnlyFlow() {
        ApprovalFlow flow = ApprovalFlow.builder().flowId(flowId).name("Finance only").isCatchAll(false).build();
        ApprovalLevel financeLevel = ApprovalLevel.builder().levelId(UUID.randomUUID()).flow(flow).levelOrder(1)
                .quorum(LevelQuorum.SEQUENTIAL).levelType(com.expense_management_service.enums.LevelType.FINANCE_VERIFICATION).build();
        financeLevel.getApprovers().add(ApprovalLevelApprover.builder().entryId(UUID.randomUUID()).level(financeLevel)
                .entryOrder(1).sourceType(ApproverSourceType.NAMED_USER).sourceReference("5100050").build());
        flow.getLevels().add(financeLevel);
        return flow;
    }

    /**
     * Reporting Manager -> Cost Center Owner -> Finance Executive, three APPROVAL levels, each a
     * single NAMED_USER entry resolved by {@code sourceReference} - lets a test address each level's
     * approver independently regardless of whether two levels happen to resolve to the same employee.
     */
    private ApprovalFlow managerThenCostCenterOwnerThenFinanceFlow(String managerApproverId, String costCenterOwnerApproverId, String financeApproverId) {
        ApprovalFlow flow = ApprovalFlow.builder().flowId(flowId).name("Manager then Cost Center Owner then Finance").isCatchAll(false).build();
        ApprovalLevel managerLevel = ApprovalLevel.builder().levelId(UUID.randomUUID()).flow(flow).levelOrder(1)
                .levelName("Reporting Manager").quorum(LevelQuorum.SEQUENTIAL).levelType(LevelType.APPROVAL).build();
        managerLevel.getApprovers().add(ApprovalLevelApprover.builder().entryId(UUID.randomUUID()).level(managerLevel)
                .entryOrder(1).sourceType(ApproverSourceType.NAMED_USER).sourceReference(managerApproverId).build());
        ApprovalLevel costCenterOwnerLevel = ApprovalLevel.builder().levelId(UUID.randomUUID()).flow(flow).levelOrder(2)
                .levelName("Cost Center Owner").quorum(LevelQuorum.SEQUENTIAL).levelType(LevelType.APPROVAL).build();
        costCenterOwnerLevel.getApprovers().add(ApprovalLevelApprover.builder().entryId(UUID.randomUUID()).level(costCenterOwnerLevel)
                .entryOrder(1).sourceType(ApproverSourceType.NAMED_USER).sourceReference(costCenterOwnerApproverId).build());
        ApprovalLevel financeLevel = ApprovalLevel.builder().levelId(UUID.randomUUID()).flow(flow).levelOrder(3)
                .levelName("Finance Executive").quorum(LevelQuorum.SEQUENTIAL).levelType(LevelType.APPROVAL).build();
        financeLevel.getApprovers().add(ApprovalLevelApprover.builder().entryId(UUID.randomUUID()).level(financeLevel)
                .entryOrder(1).sourceType(ApproverSourceType.NAMED_USER).sourceReference(financeApproverId).build());
        flow.getLevels().add(managerLevel);
        flow.getLevels().add(costCenterOwnerLevel);
        flow.getLevels().add(financeLevel);
        return flow;
    }

    /** Resolves each level's NAMED_USER entry to its own {@code sourceReference}, so distinct entries can resolve to distinct (or, deliberately, the same) approver id. */
    private void stubResolverBySourceReference() {
        when(approverSourceResolver.resolve(any(), any()))
                .thenAnswer(inv -> Optional.of(((ApprovalLevelApprover) inv.getArgument(0)).getSourceReference()));
    }

    /**
     * Bug fix regression: Reporting Manager and Cost Center Owner resolve to the SAME employee.
     * ChainCorrectnessServiceImpl (real production behavior, simulated here the same way the existing
     * split-owner test at {@code isEntireInstanceDone_waivesASkippedSplitOwnerAssignmentsOwnSplits}
     * does) auto-skips the Cost Center Owner level's only assignment as a cross-level duplicate. Before
     * the fix, activateNextEligibleLevel activated that level anyway, leaving it ACTIVE with zero live
     * assignments and the chain permanently stuck. It must instead be waived (marked COMPLETED without
     * ever activating) and the chain must proceed straight to Finance Executive.
     */
    @Test
    void reportingManagerApproval_whenCostCenterOwnerIsSamePerson_waivesCostCenterOwnerLevelAndActivatesFinance() {
        String sharedApproverId = approverId;
        String financeApproverId = "5100050";
        ExpenseReport report = draftReport();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(approvalFlowResolutionService.resolveMatchingFlow(report))
                .thenReturn(managerThenCostCenterOwnerThenFinanceFlow(sharedApproverId, sharedApproverId, financeApproverId));
        stubResolverBySourceReference();

        service.submit(reportId);

        // Simulate what ChainCorrectnessServiceImpl's real duplicate-approver pass would have done at
        // submission time - the Cost Center Owner level's assignment auto-skipped as a duplicate of
        // the Reporting Manager's (same employee, resolved at an earlier level).
        List<ApprovalAssignment> costCenterOwnerLevelAssignments = savedAssignments.stream()
                .filter(a -> a.getLevelInstance().getLevelOrder() == 2).toList();
        assertThat(costCenterOwnerLevelAssignments).hasSize(1);
        costCenterOwnerLevelAssignments.get(0).setStatus(AssignmentStatus.SKIPPED);

        service.reviewLineItem(reportId, lineItemId, sharedApproverId, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));

        ApprovalLevelInstance managerInstance = savedInstances.stream().filter(i -> i.getLevelOrder() == 1).findFirst().orElseThrow();
        ApprovalLevelInstance costCenterOwnerInstance = savedInstances.stream().filter(i -> i.getLevelOrder() == 2).findFirst().orElseThrow();
        ApprovalLevelInstance financeInstance = savedInstances.stream().filter(i -> i.getLevelOrder() == 3).findFirst().orElseThrow();

        assertThat(managerInstance.getStatus()).isEqualTo(LevelInstanceStatus.COMPLETED);
        assertThat(costCenterOwnerInstance.getStatus()).isEqualTo(LevelInstanceStatus.COMPLETED);
        assertThat(financeInstance.getStatus()).isEqualTo(LevelInstanceStatus.ACTIVE);
        assertThat(assignmentFor(financeApproverId).getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.PENDING_APPROVAL);
    }

    /**
     * Baseline/regression companion to the fix above: when Reporting Manager and Cost Center Owner are
     * DIFFERENT employees (the normal, non-duplicate case), Reporting Manager approval must activate
     * the Cost Center Owner level and its assignment exactly as before, and Finance Executive must stay
     * QUEUED until the Cost Center Owner also approves.
     */
    @Test
    void reportingManagerApproval_whenCostCenterOwnerIsDifferentPerson_activatesCostCenterOwnerLevel() {
        String managerApproverId = approverId;
        String costCenterOwnerApproverId = "5100077";
        String financeApproverId = "5100050";
        ExpenseReport report = draftReport();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(approvalFlowResolutionService.resolveMatchingFlow(report))
                .thenReturn(managerThenCostCenterOwnerThenFinanceFlow(managerApproverId, costCenterOwnerApproverId, financeApproverId));
        stubResolverBySourceReference();

        service.submit(reportId);
        service.reviewLineItem(reportId, lineItemId, managerApproverId, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));

        ApprovalLevelInstance managerInstance = savedInstances.stream().filter(i -> i.getLevelOrder() == 1).findFirst().orElseThrow();
        ApprovalLevelInstance costCenterOwnerInstance = savedInstances.stream().filter(i -> i.getLevelOrder() == 2).findFirst().orElseThrow();
        ApprovalLevelInstance financeInstance = savedInstances.stream().filter(i -> i.getLevelOrder() == 3).findFirst().orElseThrow();

        assertThat(managerInstance.getStatus()).isEqualTo(LevelInstanceStatus.COMPLETED);
        assertThat(costCenterOwnerInstance.getStatus()).isEqualTo(LevelInstanceStatus.ACTIVE);
        assertThat(assignmentFor(costCenterOwnerApproverId).getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(financeInstance.getStatus()).isEqualTo(LevelInstanceStatus.QUEUED);
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.PENDING_APPROVAL);

        service.reviewLineItem(reportId, lineItemId, costCenterOwnerApproverId, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));

        assertThat(costCenterOwnerInstance.getStatus()).isEqualTo(LevelInstanceStatus.COMPLETED);
        assertThat(financeInstance.getStatus()).isEqualTo(LevelInstanceStatus.ACTIVE);
        assertThat(assignmentFor(financeApproverId).getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
    }

    @Test
    void financeLevelCompletion_routesToApAndInvoiceHandoff_whenAnyLineItemIsClientBillable() {
        List<FinanceVerificationReview> financeReviews = new ArrayList<>();
        when(financeVerificationReviewRepository.save(any(FinanceVerificationReview.class))).thenAnswer(inv -> {
            FinanceVerificationReview r = inv.getArgument(0);
            financeReviews.add(r);
            return r;
        });
        when(financeVerificationReviewRepository.findByLevelInstance_InstanceId(any()))
                .thenAnswer(inv -> financeReviews.stream()
                        .filter(r -> r.getLevelInstance().getInstanceId().equals(inv.getArgument(0))).toList());

        ApprovalWorkflowServiceImpl serviceWithFinance = new ApprovalWorkflowServiceImpl(expenseReportRepository, approvalLevelInstanceRepository,
                approvalAssignmentRepository, approvalLineItemReviewRepository, approvalSplitReviewRepository, policyViolationRepository,
                approvalFlowResolutionService, approverSourceResolver, chainCorrectnessService, budgetEncumbranceService, delegationService,
                policyEvaluationGateway, approvalEventPublisher, slaPolicyService,
                new com.expense_management_service.mapper.PolicyViolationMapper(),
                List.of(new ApprovalReviewStrategy(approvalLineItemReviewRepository),
                        new FinanceVerificationStrategy(financeVerificationReviewRepository, verificationQueryRepository)),
                new ExpenseReportResponseFactory(new ExpenseReportMapper(), policyViolationRepository),
                materialChangeEvaluator);

        ExpenseReport report = draftReport();
        report.getExpenseLineItems().get(0).setClientBillable(true);
        report.setTotalAmount(new java.math.BigDecimal("50000"));
        report.setFiscalYear("2026");
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(financeOnlyFlow());
        when(approverSourceResolver.resolve(any(), any())).thenReturn(Optional.of("5100050"));

        serviceWithFinance.submit(reportId);

        ApprovalLevelInstance financeInstance = savedInstances.stream()
                .filter(i -> i.getLevelType() == com.expense_management_service.enums.LevelType.FINANCE_VERIFICATION)
                .findFirst().orElseThrow();
        FinanceVerificationReview review = financeReviews.stream().findFirst().orElseThrow();
        review.setStatus(FinanceVerificationStatus.VERIFIED);

        serviceWithFinance.advanceAfterLevelReviewed(reportId, financeInstance.getInstanceId(), "5100050");

        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.APPROVED);
        // Client-billable still reimburses the employee via AP; invoicing is a parallel track.
        assertThat(report.getPaymentRoutingStatus())
                .isEqualTo(com.expense_management_service.enums.PaymentRoutingStatus.APPROVED_FOR_PAYMENT);
        assertThat(report.getInvoiceHandoffStatus())
                .isEqualTo(com.expense_management_service.enums.InvoiceHandoffStatus.PENDING);
    }

    @Test
    void financeLevelCompletion_routesToApprovedForPayment_whenNoLineItemIsClientBillable() {
        List<FinanceVerificationReview> financeReviews = new ArrayList<>();
        when(financeVerificationReviewRepository.save(any(FinanceVerificationReview.class))).thenAnswer(inv -> {
            FinanceVerificationReview r = inv.getArgument(0);
            financeReviews.add(r);
            return r;
        });
        when(financeVerificationReviewRepository.findByLevelInstance_InstanceId(any()))
                .thenAnswer(inv -> financeReviews.stream()
                        .filter(r -> r.getLevelInstance().getInstanceId().equals(inv.getArgument(0))).toList());

        ApprovalWorkflowServiceImpl serviceWithFinance = new ApprovalWorkflowServiceImpl(expenseReportRepository, approvalLevelInstanceRepository,
                approvalAssignmentRepository, approvalLineItemReviewRepository, approvalSplitReviewRepository, policyViolationRepository,
                approvalFlowResolutionService, approverSourceResolver, chainCorrectnessService, budgetEncumbranceService, delegationService,
                policyEvaluationGateway, approvalEventPublisher, slaPolicyService,
                new com.expense_management_service.mapper.PolicyViolationMapper(),
                List.of(new ApprovalReviewStrategy(approvalLineItemReviewRepository),
                        new FinanceVerificationStrategy(financeVerificationReviewRepository, verificationQueryRepository)),
                new ExpenseReportResponseFactory(new ExpenseReportMapper(), policyViolationRepository),
                materialChangeEvaluator);

        ExpenseReport report = draftReport();
        report.setTotalAmount(new java.math.BigDecimal("50000"));
        report.setFiscalYear("2026");
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(financeOnlyFlow());
        when(approverSourceResolver.resolve(any(), any())).thenReturn(Optional.of("5100050"));

        serviceWithFinance.submit(reportId);

        ApprovalLevelInstance financeInstance = savedInstances.stream()
                .filter(i -> i.getLevelType() == com.expense_management_service.enums.LevelType.FINANCE_VERIFICATION)
                .findFirst().orElseThrow();
        FinanceVerificationReview review = financeReviews.stream().findFirst().orElseThrow();
        review.setStatus(FinanceVerificationStatus.VERIFIED);

        serviceWithFinance.advanceAfterLevelReviewed(reportId, financeInstance.getInstanceId(), "5100050");

        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.APPROVED);
        assertThat(report.getPaymentRoutingStatus())
                .isEqualTo(com.expense_management_service.enums.PaymentRoutingStatus.APPROVED_FOR_PAYMENT);
    }

    @Test
    void submit_snapshotsLevelTypeOntoEveryInstance_forAMixedManagerFinanceFlow() {
        ExpenseReport report = draftReport();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(managerThenFinanceFlow());
        when(approverSourceResolver.resolve(any(), any())).thenReturn(Optional.of(approverId));

        ExpenseReportResponse response = service.submit(reportId);

        assertThat(response.reportStatus()).isEqualTo(ReportStatus.PENDING_APPROVAL.name());
        assertThat(savedInstances).hasSize(2);
        var managerInstance = savedInstances.stream().filter(i -> i.getLevelOrder() == 1).findFirst().orElseThrow();
        var financeInstance = savedInstances.stream().filter(i -> i.getLevelOrder() == 2).findFirst().orElseThrow();
        assertThat(managerInstance.getLevelType()).isEqualTo(com.expense_management_service.enums.LevelType.APPROVAL);
        assertThat(managerInstance.getStatus()).isEqualTo(LevelInstanceStatus.ACTIVE);
        assertThat(financeInstance.getLevelType()).isEqualTo(com.expense_management_service.enums.LevelType.FINANCE_VERIFICATION);
        assertThat(financeInstance.getStatus()).isEqualTo(LevelInstanceStatus.QUEUED);
    }

    @Test
    void submit_throws_whenNotInDraft() {
        ExpenseReport report = draftReport();
        report.setReportStatus(ReportStatus.APPROVED);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.submit(reportId)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submit_throws_whenALevelResolvesZeroApprovers() {
        ExpenseReport report = draftReport();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(singleLevelFlow());
        when(approverSourceResolver.resolve(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(reportId)).isInstanceOf(IllegalStateException.class);
    }

    private ExpenseReport submittedReport() {
        ExpenseReport report = draftReport();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(singleLevelFlow());
        when(approverSourceResolver.resolve(any(), any())).thenReturn(Optional.of(approverId));
        service.submit(reportId);
        return report;
    }

    @Test
    void reviewLineItem_approvingTheOnlyLineItem_completesTheLevelAndApprovesTheReport() {
        ExpenseReport report = submittedReport();

        ExpenseReportResponse response = service.reviewLineItem(reportId, lineItemId, approverId,
                new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));

        assertThat(response.reportStatus()).isEqualTo(ReportStatus.APPROVED.name());
        assertThat(savedInstances.get(0).getStatus()).isEqualTo(LevelInstanceStatus.COMPLETED);
        assertThat(report.getApprovedAt()).isNotNull();
    }

    @Test
    void reviewLineItem_needsCorrection_movesReportToAwaitingCorrection_andRequiresAComment() {
        submittedReport();

        assertThatThrownBy(() -> service.reviewLineItem(reportId, lineItemId, approverId,
                new LineItemReviewRequest(LineItemReviewStatus.NEEDS_CORRECTION, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("comment is required");

        ExpenseReportResponse response = service.reviewLineItem(reportId, lineItemId, approverId,
                new LineItemReviewRequest(LineItemReviewStatus.NEEDS_CORRECTION, "Missing receipt"));

        assertThat(response.reportStatus()).isEqualTo(ReportStatus.AWAITING_CORRECTION.name());
    }

    @Test
    void reviewLineItem_throwsAccessDenied_whenActorIsNotTheResolvedApprover() {
        submittedReport();

        assertThatThrownBy(() -> service.reviewLineItem(reportId, lineItemId, "someone-else",
                new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void resubmit_resumesInPlace_whenSameFlowStillMatches() {
        ExpenseReport report = submittedReport();
        service.reviewLineItem(reportId, lineItemId, approverId, new LineItemReviewRequest(LineItemReviewStatus.NEEDS_CORRECTION, "fix it"));
        report.setReportStatus(ReportStatus.AWAITING_CORRECTION);

        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(singleLevelFlow());

        ExpenseReportResponse response = service.submit(reportId);

        assertThat(response.reportStatus()).isEqualTo(ReportStatus.PENDING_APPROVAL.name());
        assertThat(savedInstances).hasSize(1); // no new instance created - resumed in place
        assertThat(savedReviews.get(0).getStatus()).isEqualTo(LineItemReviewStatus.PENDING);
    }

    @Test
    void resubmit_fullyRestarts_whenADifferentFlowNowMatches() {
        ExpenseReport report = submittedReport();
        service.reviewLineItem(reportId, lineItemId, approverId, new LineItemReviewRequest(LineItemReviewStatus.NEEDS_CORRECTION, "fix it"));
        report.setReportStatus(ReportStatus.AWAITING_CORRECTION);

        ApprovalFlow differentFlow = singleLevelFlow();
        differentFlow.setFlowId(UUID.randomUUID());
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(differentFlow);

        service.submit(reportId);

        assertThat(savedInstances).hasSize(2); // old cycle's instance + a fresh one
        assertThat(savedInstances.stream().filter(i -> i.getSubmissionCycle() == 1).findFirst().orElseThrow().getStatus())
                .isEqualTo(LevelInstanceStatus.CANCELLED);
        assertThat(savedInstances.stream().anyMatch(i -> i.getSubmissionCycle() == 2 && i.getFlowId().equals(differentFlow.getFlowId()))).isTrue();
    }

    @Test
    void recall_returnsToDraft_beforeAnyLevelApproved() {
        submittedReport();

        ExpenseReportResponse response = service.recall(reportId, submitterId);

        assertThat(response.reportStatus()).isEqualTo(ReportStatus.DRAFT.name());
    }

    @Test
    void recall_blocked_onceALevelHasAlreadyApproved() {
        ExpenseReport report = submittedReport();
        service.reviewLineItem(reportId, lineItemId, approverId, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        report.setReportStatus(ReportStatus.APPROVED); // simulate the persisted state after full approval

        // A fully APPROVED report also fails the recall status check itself - use a report that's
        // still nominally PENDING_APPROVAL/AWAITING_CORRECTION but already has a COMPLETED level,
        // which is the actual scenario the guard protects against on a multi-level flow.
        report.setReportStatus(ReportStatus.PENDING_APPROVAL);

        assertThatThrownBy(() -> service.recall(reportId, submitterId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already completed");
    }

    @Test
    void recall_throws_whenActorIsNotTheOwner() {
        submittedReport();

        assertThatThrownBy(() -> service.recall(reportId, "not-the-owner"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void rejectReport_isTerminal() {
        submittedReport();

        ExpenseReportResponse response = service.rejectReport(reportId, approverId, new RejectReportRequest("Duplicate submission"));

        assertThat(response.reportStatus()).isEqualTo(ReportStatus.REJECTED.name());
        assertThat(savedInstances.get(0).getStatus()).isEqualTo(LevelInstanceStatus.CANCELLED);
        assertThat(savedAssignments.get(0).getStatus()).isEqualTo(AssignmentStatus.SUPERSEDED);
    }

    /**
     * Regression for the reject/recall/cancel path leaving sibling co-approver assignments ACTIVE
     * under ANY_OF/ALL_OF quorum: {@code cancelAllOpenInstances} used to only cancel the {@code
     * ApprovalLevelInstance}, never the still-open {@code ApprovalAssignment} rows on it, so a
     * co-approver's assignment-status-based "My Queue" kept showing a report whose approval had
     * already terminated.
     */
    @Test
    void rejectReport_closesEveryCoApproversAssignment_underAnyOfQuorum_soTheyNoLongerSeeItInMyQueue() {
        String coApproverId = "5100099";
        ExpenseReport report = draftReport();
        ApprovalFlow flow = ApprovalFlow.builder().flowId(flowId).name("Two-approver ANY_OF").isCatchAll(false).build();
        ApprovalLevel level = ApprovalLevel.builder().levelId(UUID.randomUUID()).flow(flow).levelOrder(1).quorum(LevelQuorum.ANY_OF).build();
        ApprovalLevelApprover entry1 = ApprovalLevelApprover.builder().entryId(UUID.randomUUID()).level(level)
                .entryOrder(1).sourceType(ApproverSourceType.NAMED_USER).sourceReference(approverId).build();
        ApprovalLevelApprover entry2 = ApprovalLevelApprover.builder().entryId(UUID.randomUUID()).level(level)
                .entryOrder(2).sourceType(ApproverSourceType.NAMED_USER).sourceReference(coApproverId).build();
        level.getApprovers().add(entry1);
        level.getApprovers().add(entry2);
        flow.getLevels().add(level);

        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(flow);
        when(approverSourceResolver.resolve(eq(entry1), any())).thenReturn(Optional.of(approverId));
        when(approverSourceResolver.resolve(eq(entry2), any())).thenReturn(Optional.of(coApproverId));

        service.submit(reportId);
        assertThat(savedAssignments).hasSize(2).allMatch(a -> a.getStatus() == AssignmentStatus.ACTIVE);
        assertThat(service.getMyQueue(coApproverId, PageRequest.of(0, 20)).content()).hasSize(1);

        service.rejectReport(reportId, approverId, new RejectReportRequest("Policy violation"));

        assertThat(savedAssignments).noneMatch(a -> a.getStatus() == AssignmentStatus.ACTIVE || a.getStatus() == AssignmentStatus.PENDING);
        assertThat(service.getMyQueue(coApproverId, PageRequest.of(0, 20)).content()).isEmpty();
    }

    @Test
    void bulkApprove_throws_whenReportHasPolicyViolations() {
        submittedReport();
        when(policyViolationRepository.findByLineItem_Report_ReportId(reportId)).thenReturn(
                List.of(com.expense_management_service.entity.PolicyViolation.builder().build()));

        assertThatThrownBy(() -> service.bulkApprove(reportId, approverId)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bulkApprove_approvesEveryPendingLineItem_whenEligible() {
        submittedReport();

        ExpenseReportResponse response = service.bulkApprove(reportId, approverId);

        assertThat(response.reportStatus()).isEqualTo(ReportStatus.APPROVED.name());
    }

    // ---------------------------------------------------------------------
    // §14 backend gaps: line-item-reviews, status, my-history
    // ---------------------------------------------------------------------

    @Test
    void getApprovalStatus_returnsCurrentLevelAndEligibility_forPendingReport() {
        submittedReport();

        var status = service.getApprovalStatus(reportId);

        assertThat(status.currentLevelOrder()).isEqualTo(1);
        assertThat(status.currentLevelDisplayName()).isEqualTo("Level 1"); // no levelName set on the test's singleLevelFlow()
        assertThat(status.totalLevels()).isEqualTo(1);
        assertThat(status.canRecall()).isTrue();
        assertThat(status.canCancel()).isTrue();
    }

    @Test
    void getApprovalStatus_disallowsRecallAndCancel_onceALevelHasApproved() {
        ExpenseReport report = submittedReport();
        service.reviewLineItem(reportId, lineItemId, approverId, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        // Simulate a later level still pending on a multi-level flow, so status isn't yet a final outcome.
        report.setReportStatus(ReportStatus.PENDING_APPROVAL);

        var status = service.getApprovalStatus(reportId);

        assertThat(status.canRecall()).isFalse();
        assertThat(status.canCancel()).isFalse();
    }

    @Test
    void getLineItemReviews_visibleToOwner() {
        submittedReport();

        var reviews = service.getLineItemReviews(reportId, submitterId);

        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).status()).isEqualTo(LineItemReviewStatus.PENDING);
        assertThat(reviews.get(0).levelOrder()).isEqualTo(1);
        assertThat(reviews.get(0).displayName()).isEqualTo("Level 1");
    }

    @Test
    void getLineItemReviews_visibleToCurrentApprover() {
        submittedReport();

        var reviews = service.getLineItemReviews(reportId, approverId);

        assertThat(reviews).hasSize(1);
    }

    @Test
    void getLineItemReviews_throwsAccessDenied_forUnrelatedEmployee() {
        submittedReport();

        assertThatThrownBy(() -> service.getLineItemReviews(reportId, "someone-unrelated"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void getLineItemReviews_showsCommentAfterNeedsCorrection() {
        submittedReport();
        service.reviewLineItem(reportId, lineItemId, approverId, new LineItemReviewRequest(LineItemReviewStatus.NEEDS_CORRECTION, "Missing receipt"));

        var reviews = service.getLineItemReviews(reportId, submitterId);

        assertThat(reviews.get(0).status()).isEqualTo(LineItemReviewStatus.NEEDS_CORRECTION);
        assertThat(reviews.get(0).comment()).isEqualTo("Missing receipt");
    }

    @Test
    void getMyHistory_returnsApprovedReports_whereCallerHasACompletedAssignment() {
        submittedReport();
        service.reviewLineItem(reportId, lineItemId, approverId, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));

        var history = service.getMyHistory(approverId, "APPROVED", PageRequest.of(0, 20));

        assertThat(history.content()).hasSize(1);
        assertThat(history.content().get(0).reportStatus()).isEqualTo(ReportStatus.APPROVED.name());
        assertThat(history.totalElements()).isEqualTo(1);
    }

    @Test
    void getMyHistory_returnsRejectedReports_whereCallerRejected() {
        submittedReport();
        service.rejectReport(reportId, approverId, new RejectReportRequest("Duplicate submission"));

        var history = service.getMyHistory(approverId, "REJECTED", PageRequest.of(0, 20));

        assertThat(history.content()).hasSize(1);
        assertThat(history.content().get(0).reportStatus()).isEqualTo(ReportStatus.REJECTED.name());
    }

    @Test
    void getMyHistory_excludesRejected_whenFilteredToApprovedOnly() {
        submittedReport();
        service.rejectReport(reportId, approverId, new RejectReportRequest("Duplicate submission"));

        var history = service.getMyHistory(approverId, "APPROVED", PageRequest.of(0, 20));

        assertThat(history.content()).isEmpty();
    }

    @Test
    void getMyHistory_returnsBoth_whenOutcomeOmitted() {
        submittedReport();
        service.reviewLineItem(reportId, lineItemId, approverId, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));

        var history = service.getMyHistory(approverId, null, PageRequest.of(0, 20));

        assertThat(history.content()).hasSize(1);
    }

    // ---- getMyQueue() - paginated (§14) ----

    @Test
    void getMyQueue_returnsReportWithActiveAssignment_forTheResolvedApprover() {
        submittedReport();

        var queue = service.getMyQueue(approverId, PageRequest.of(0, 20));

        assertThat(queue.content()).hasSize(1);
        assertThat(queue.content().get(0).reportId()).isEqualTo(reportId);
        assertThat(queue.totalElements()).isEqualTo(1);
    }

    @Test
    void getMyQueue_isEmpty_forAnEmployeeWithNoActiveAssignment() {
        submittedReport();

        var queue = service.getMyQueue("someone-unrelated", PageRequest.of(0, 20));

        assertThat(queue.content()).isEmpty();
    }

    @Test
    void getMyQueue_resolvesReportsForEveryApproverIdTheCallerActsFor() {
        submittedReport();
        // A delegate acting for the resolved approver must see the same report in their own queue.
        when(delegationService.resolveApproverIdsActingFor("delegate-of-approver"))
                .thenReturn(Set.of("delegate-of-approver", approverId));

        var queue = service.getMyQueue("delegate-of-approver", PageRequest.of(0, 20));

        assertThat(queue.content()).hasSize(1);
    }

    // ---------------------------------------------------------------------
    // Split-aware Cost Center Owner resolution (Phase 4)
    // ---------------------------------------------------------------------

    private static final String HEADER_OWNER = "5100060";
    private static final String OWNER_A = "5100061";
    private static final String OWNER_B = "5100062";
    private static final String OWNER_C = "5100063";

    private CostCenter costCenterOwnedBy(String ownerEmployeeId) {
        return CostCenter.builder().costCenterId(UUID.randomUUID()).costCenterCode("CC-" + ownerEmployeeId)
                .ownerEmployeeId(ownerEmployeeId).build();
    }

    private ExpenseSplit split(ExpenseLineItem lineItem, CostCenter costCenter, String amount, int order) {
        return ExpenseSplit.builder().splitId(UUID.randomUUID()).lineItem(lineItem).costCenter(costCenter)
                .splitType(SplitType.FIXED_AMOUNT).allocatedAmount(new java.math.BigDecimal(amount)).splitOrder(order).build();
    }

    /** One APPROVAL level with a single COST_CENTER_OWNER entry - {@code quorum} only governs the header (normal-track) entry; split-owner assignments always activate in parallel regardless (Phase 4 Decision 1). */
    private ApprovalFlow costCenterOwnerOnlyFlow(LevelQuorum quorum) {
        ApprovalFlow flow = ApprovalFlow.builder().flowId(flowId).name("Cost Center Owner").isCatchAll(false).build();
        ApprovalLevel level = ApprovalLevel.builder().levelId(UUID.randomUUID()).flow(flow).levelOrder(1)
                .quorum(quorum).levelType(LevelType.APPROVAL).build();
        level.getApprovers().add(ApprovalLevelApprover.builder().entryId(UUID.randomUUID()).level(level)
                .sourceType(ApproverSourceType.COST_CENTER_OWNER).build());
        flow.getLevels().add(level);
        return flow;
    }

    /** SEQUENTIAL level: entryOrder=1 NAMED_USER (approverId) THEN a COST_CENTER_OWNER entry (entryOrder null, sorts last under nullsLast) - proves split-owner assignments jump ahead of the normal SEQUENTIAL order. */
    private ApprovalFlow namedUserThenCostCenterOwnerSequentialFlow() {
        ApprovalFlow flow = ApprovalFlow.builder().flowId(flowId).name("Named user then Cost Center Owner").isCatchAll(false).build();
        ApprovalLevel level = ApprovalLevel.builder().levelId(UUID.randomUUID()).flow(flow).levelOrder(1)
                .quorum(LevelQuorum.SEQUENTIAL).levelType(LevelType.APPROVAL).build();
        level.getApprovers().add(ApprovalLevelApprover.builder().entryId(UUID.randomUUID()).level(level)
                .entryOrder(1).sourceType(ApproverSourceType.NAMED_USER).sourceReference(approverId).build());
        level.getApprovers().add(ApprovalLevelApprover.builder().entryId(UUID.randomUUID()).level(level)
                .sourceType(ApproverSourceType.COST_CENTER_OWNER).build());
        flow.getLevels().add(level);
        return flow;
    }

    private ApprovalAssignment assignmentFor(String ownerEmployeeId) {
        return savedAssignments.stream().filter(a -> a.getApproverId().equals(ownerEmployeeId)).findFirst().orElseThrow();
    }

    /** Disambiguates an approver id that resolves at more than one level (e.g. the Reporting Manager also being a split Cost Center Owner). */
    private ApprovalAssignment assignmentAtLevel(String approverId, int levelOrder) {
        return savedAssignments.stream()
                .filter(a -> a.getApproverId().equals(approverId))
                .filter(a -> a.getLevelInstance().getLevelOrder() == levelOrder)
                .findFirst().orElseThrow();
    }

    private ApprovalLevelInstance instanceAtLevel(int levelOrder) {
        return savedInstances.stream().filter(i -> i.getLevelOrder() == levelOrder).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------------
    // Duplicate approver + split allocation interaction (production bug: "skip duplicate approvers,
    // NOT duplicate business requirements" - a Cost Center Owner level with split allocations must
    // only waive the SPECIFIC split-owner whose employee already appears earlier in the chain, never
    // the whole level, as long as at least one other split owner's assignment is still actionable.
    // ---------------------------------------------------------------------

    /**
     * Reporting Manager (NAMED_USER) -> Cost Center Owner (single COST_CENTER_OWNER entry, split-aware)
     * -> Finance Executive (NAMED_USER). The Cost Center Owner level's own header entry deliberately
     * resolves to nothing (see {@link #stubResolverForSplitScenario}) - the report is fully split
     * across Cost Centers, so Level 2 is made up entirely of per-split-owner assignments.
     */
    private ApprovalFlow managerThenSplitCostCenterOwnerThenFinanceFlow(String managerApproverId, String financeApproverId) {
        ApprovalFlow flow = ApprovalFlow.builder().flowId(flowId).name("Manager then Split Cost Center Owner then Finance").isCatchAll(false).build();
        ApprovalLevel managerLevel = ApprovalLevel.builder().levelId(UUID.randomUUID()).flow(flow).levelOrder(1)
                .levelName("Reporting Manager").quorum(LevelQuorum.SEQUENTIAL).levelType(LevelType.APPROVAL).build();
        managerLevel.getApprovers().add(ApprovalLevelApprover.builder().entryId(UUID.randomUUID()).level(managerLevel)
                .entryOrder(1).sourceType(ApproverSourceType.NAMED_USER).sourceReference(managerApproverId).build());
        ApprovalLevel costCenterOwnerLevel = ApprovalLevel.builder().levelId(UUID.randomUUID()).flow(flow).levelOrder(2)
                .levelName("Cost Center Owner").quorum(LevelQuorum.ANY_OF).levelType(LevelType.APPROVAL).build();
        costCenterOwnerLevel.getApprovers().add(ApprovalLevelApprover.builder().entryId(UUID.randomUUID()).level(costCenterOwnerLevel)
                .sourceType(ApproverSourceType.COST_CENTER_OWNER).build());
        ApprovalLevel financeLevel = ApprovalLevel.builder().levelId(UUID.randomUUID()).flow(flow).levelOrder(3)
                .levelName("Finance Executive").quorum(LevelQuorum.SEQUENTIAL).levelType(LevelType.APPROVAL).build();
        financeLevel.getApprovers().add(ApprovalLevelApprover.builder().entryId(UUID.randomUUID()).level(financeLevel)
                .entryOrder(1).sourceType(ApproverSourceType.NAMED_USER).sourceReference(financeApproverId).build());
        flow.getLevels().add(managerLevel);
        flow.getLevels().add(costCenterOwnerLevel);
        flow.getLevels().add(financeLevel);
        return flow;
    }

    /** NAMED_USER entries resolve to their sourceReference; the level-wide COST_CENTER_OWNER (header) entry resolves to nothing, since these fixtures always fully split the report. */
    private void stubResolverForSplitScenario() {
        when(approverSourceResolver.resolve(any(), any())).thenAnswer(inv -> {
            ApprovalLevelApprover entry = inv.getArgument(0);
            return entry.getSourceType() == ApproverSourceType.COST_CENTER_OWNER
                    ? Optional.empty()
                    : Optional.of(entry.getSourceReference());
        });
    }

    /**
     * End-to-end regression for the exact reported scenario: Reporting Manager A is ALSO one of
     * several split Cost Center Owners (Engineering). Per §2.6, A's own Engineering assignment must be
     * auto-skipped as a duplicate of A's Level 1 sign-off - but HR/Owner B and DevOps/Owner C are
     * DIFFERENT employees with a genuine, distinct business requirement to approve their own
     * allocation, and must remain fully actionable. The Cost Center Owner level as a whole must
     * activate (not be waived) because live work remains on it.
     */
    @Test
    void reportingManagerIsOneOfMultipleSplitCostCenterOwners_othersRemainActionableAndLevelCompletesNormally() {
        String reportingManager = OWNER_A; // also Engineering's Cost Center Owner
        String financeApproverId = "5100050";
        ExpenseReport report = draftReport();
        ExpenseLineItem lineItem = report.getExpenseLineItems().get(0);
        ExpenseSplit engineering = split(lineItem, costCenterOwnedBy(OWNER_A), "500", 1);
        ExpenseSplit hr = split(lineItem, costCenterOwnedBy(OWNER_B), "300", 2);
        ExpenseSplit devOps = split(lineItem, costCenterOwnedBy(OWNER_C), "200", 3);
        lineItem.setExpenseSplits(List.of(engineering, hr, devOps));
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(approvalFlowResolutionService.resolveMatchingFlow(report))
                .thenReturn(managerThenSplitCostCenterOwnerThenFinanceFlow(reportingManager, financeApproverId));
        stubResolverForSplitScenario();

        service.submit(reportId);
        assertThat(instanceAtLevel(1).getStatus()).isEqualTo(LevelInstanceStatus.ACTIVE);

        // Simulate ChainCorrectnessServiceImpl's real duplicate-approver pass, which runs at
        // submission time - BEFORE Level 1 ever activates, let alone completes. A's Engineering
        // split-owner assignment is the LATER occurrence of an already-seen employee (A, at Level 1),
        // so it alone is auto-skipped here - B's and C's assignments are untouched.
        assignmentAtLevel(OWNER_A, 2).setStatus(AssignmentStatus.SKIPPED);

        // STEP 1: A approves Level 1 (Reporting Manager) - this is what actually triggers Level 2's activation.
        service.reviewLineItem(reportId, lineItemId, reportingManager, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));

        // STEP 2/3: Level 2 must activate (not be waived) - HR/B and DevOps/C are still actionable.
        assertThat(instanceAtLevel(1).getStatus()).isEqualTo(LevelInstanceStatus.COMPLETED);
        assertThat(instanceAtLevel(2).getStatus()).isEqualTo(LevelInstanceStatus.ACTIVE);
        assertThat(assignmentAtLevel(OWNER_A, 2).getStatus()).isEqualTo(AssignmentStatus.SKIPPED);
        assertThat(assignmentAtLevel(OWNER_B, 2).getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(assignmentAtLevel(OWNER_C, 2).getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(instanceAtLevel(3).getStatus()).isEqualTo(LevelInstanceStatus.QUEUED);

        // STEP 4: B and C approve their own split allocations - A is never asked again.
        UUID hrSplitId = assignmentAtLevel(OWNER_B, 2).getSplitReviews().get(0).getSplit().getSplitId();
        UUID devOpsSplitId = assignmentAtLevel(OWNER_C, 2).getSplitReviews().get(0).getSplit().getSplitId();
        service.reviewSplit(reportId, hrSplitId, OWNER_B, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        service.reviewSplit(reportId, devOpsSplitId, OWNER_C, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));

        // STEP 5/6: Level 2 completes; Finance Executive activates.
        assertThat(instanceAtLevel(2).getStatus()).isEqualTo(LevelInstanceStatus.COMPLETED);
        assertThat(instanceAtLevel(3).getStatus()).isEqualTo(LevelInstanceStatus.ACTIVE);
        assertThat(assignmentFor(financeApproverId).getStatus()).isEqualTo(AssignmentStatus.ACTIVE);

        // STEP 7/8: Finance completes; report reaches its final APPROVED status.
        service.reviewLineItem(reportId, lineItemId, financeApproverId, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        assertThat(instanceAtLevel(3).getStatus()).isEqualTo(LevelInstanceStatus.COMPLETED);
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.APPROVED);
    }

    /**
     * When EVERY split Cost Center Owner happens to duplicate the Reporting Manager (not just one of
     * several), the Cost Center Owner level legitimately has zero actionable assignments left and must
     * be waived entirely - this is the "Case 1/6" (no split, or all-splits-duplicate) counterpart to
     * the mixed case above, proving the fix's "ALL must be skipped" check (not "ANY") still waives a
     * level correctly when that really is the outcome for every one of its assignments.
     */
    @Test
    void reportingManagerIsTheOnlySplitCostCenterOwner_wholeLevelIsWaived() {
        String reportingManager = OWNER_A;
        String financeApproverId = "5100050";
        ExpenseReport report = draftReport();
        ExpenseLineItem lineItem = report.getExpenseLineItems().get(0);
        // Two splits, same owner - createSplitOwnerAssignments combines them into ONE assignment
        // (Case 4's existing, intended design), so skipping it skips 100% of Level 2's coverage.
        ExpenseSplit engineering = split(lineItem, costCenterOwnedBy(OWNER_A), "600", 1);
        ExpenseSplit alsoEngineering = split(lineItem, costCenterOwnedBy(OWNER_A), "400", 2);
        lineItem.setExpenseSplits(List.of(engineering, alsoEngineering));
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(approvalFlowResolutionService.resolveMatchingFlow(report))
                .thenReturn(managerThenSplitCostCenterOwnerThenFinanceFlow(reportingManager, financeApproverId));
        stubResolverForSplitScenario();

        service.submit(reportId);
        assertThat(assignmentAtLevel(OWNER_A, 2).getSplitReviews()).hasSize(2); // combined into one assignment, per Case 4

        // Simulate ChainCorrectnessServiceImpl's real duplicate-approver pass (runs at submission
        // time, before Level 1 ever activates) - A is the ONLY Cost Center Owner across both splits,
        // and already appeared at Level 1, so this single combined assignment is the entirety of
        // Level 2's coverage and gets skipped.
        assignmentAtLevel(OWNER_A, 2).setStatus(AssignmentStatus.SKIPPED);

        // A approves Level 1 - this triggers the transition into (and, per the fix, straight past) Level 2.
        service.reviewLineItem(reportId, lineItemId, reportingManager, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));

        assertThat(instanceAtLevel(1).getStatus()).isEqualTo(LevelInstanceStatus.COMPLETED);
        assertThat(instanceAtLevel(2).getStatus()).isEqualTo(LevelInstanceStatus.COMPLETED); // waived, never ACTIVE
        assertThat(instanceAtLevel(3).getStatus()).isEqualTo(LevelInstanceStatus.ACTIVE);
        assertThat(assignmentFor(financeApproverId).getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.PENDING_APPROVAL);
    }

    @Test
    void submit_splitReport_createsHeaderAssignmentPlusOneCombinedAssignmentPerDistinctSplitOwner() {
        ExpenseReport report = draftReport();
        ExpenseLineItem lineItem = report.getExpenseLineItems().get(0);
        ExpenseSplit splitA = split(lineItem, costCenterOwnedBy(OWNER_A), "600", 1);
        ExpenseSplit splitB = split(lineItem, costCenterOwnedBy(OWNER_B), "400", 2);
        lineItem.setExpenseSplits(List.of(splitA, splitB));
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(costCenterOwnerOnlyFlow(LevelQuorum.ANY_OF));
        when(approverSourceResolver.resolve(any(), any())).thenReturn(Optional.of(HEADER_OWNER));

        service.submit(reportId);

        assertThat(savedAssignments).hasSize(3);
        assertThat(savedAssignments).extracting(ApprovalAssignment::getApproverId)
                .containsExactlyInAnyOrder(HEADER_OWNER, OWNER_A, OWNER_B);
        assertThat(savedAssignments).allMatch(a -> a.getStatus() == AssignmentStatus.ACTIVE);
        assertThat(assignmentFor(HEADER_OWNER).getSplitReviews()).isEmpty();
        assertThat(assignmentFor(OWNER_A).getSplitReviews()).hasSize(1);
        assertThat(assignmentFor(OWNER_B).getSplitReviews()).hasSize(1);
        assertThat(savedSplitReviews).hasSize(2).allMatch(r -> r.getStatus() == LineItemReviewStatus.PENDING);
    }

    @Test
    void submit_splitsResolvingToTheSameOwner_combineIntoOneAssignmentWithMultipleSplitReviews() {
        ExpenseReport report = draftReport();
        ExpenseLineItem lineItem = report.getExpenseLineItems().get(0);
        CostCenter ccOwnedByA1 = costCenterOwnedBy(OWNER_A);
        CostCenter ccOwnedByA2 = costCenterOwnedBy(OWNER_A);
        ExpenseSplit split1 = split(lineItem, ccOwnedByA1, "600", 1);
        ExpenseSplit split2 = split(lineItem, ccOwnedByA2, "400", 2);
        lineItem.setExpenseSplits(List.of(split1, split2));
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(costCenterOwnerOnlyFlow(LevelQuorum.ANY_OF));
        when(approverSourceResolver.resolve(any(), any())).thenReturn(Optional.of(HEADER_OWNER));

        service.submit(reportId);

        assertThat(savedAssignments).hasSize(2); // header + ONE combined assignment for owner A, not two
        assertThat(assignmentFor(OWNER_A).getSplitReviews()).hasSize(2);
    }

    @Test
    void activateLevel_splitOwnerAssignmentsActivateImmediately_evenWhenSequentialEntryOrderWouldDeferThem() {
        ExpenseReport report = draftReport();
        ExpenseLineItem lineItem = report.getExpenseLineItems().get(0);
        ExpenseSplit splitA = split(lineItem, costCenterOwnedBy(OWNER_A), "1000", 1);
        lineItem.setExpenseSplits(List.of(splitA));
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(namedUserThenCostCenterOwnerSequentialFlow());
        when(approverSourceResolver.resolve(any(), any())).thenAnswer(inv -> {
            ApprovalLevelApprover entry = inv.getArgument(0);
            return entry.getSourceType() == ApproverSourceType.NAMED_USER
                    ? Optional.of(entry.getSourceReference()) : Optional.of(HEADER_OWNER);
        });

        service.submit(reportId);

        // approverId is entryOrder=1 -> activates now (SEQUENTIAL, as always). The header
        // COST_CENTER_OWNER entry has no entryOrder, sorts last, and stays PENDING until approverId
        // completes - unaffected by Phase 4. But the split-owner assignment for OWNER_A must already
        // be ACTIVE despite SEQUENTIAL quorum, per Decision 1.
        assertThat(assignmentFor(approverId).getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(assignmentFor(HEADER_OWNER).getStatus()).isEqualTo(AssignmentStatus.PENDING);
        assertThat(assignmentFor(OWNER_A).getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
    }

    private ExpenseReport submittedSplitReport(String ownerAAmount, String ownerBAmount) {
        ExpenseReport report = draftReport();
        ExpenseLineItem lineItem = report.getExpenseLineItems().get(0);
        ExpenseSplit splitA = split(lineItem, costCenterOwnedBy(OWNER_A), ownerAAmount, 1);
        ExpenseSplit splitB = split(lineItem, costCenterOwnedBy(OWNER_B), ownerBAmount, 2);
        lineItem.setExpenseSplits(List.of(splitA, splitB));
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(costCenterOwnerOnlyFlow(LevelQuorum.ANY_OF));
        when(approverSourceResolver.resolve(any(), any())).thenReturn(Optional.of(HEADER_OWNER));
        service.submit(reportId);
        return report;
    }

    private UUID splitIdFor(String ownerEmployeeId) {
        return assignmentFor(ownerEmployeeId).getSplitReviews().get(0).getSplit().getSplitId();
    }

    @Test
    void reviewSplit_completesOwnAssignment_butLevelWaitsUntilLineItemsAndAllOwnersAreDone() {
        ExpenseReport report = submittedSplitReport("600", "400");

        service.reviewSplit(reportId, splitIdFor(OWNER_A), OWNER_A, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        assertThat(assignmentFor(OWNER_A).getStatus()).isEqualTo(AssignmentStatus.COMPLETED);
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.PENDING_APPROVAL); // owner B and the header's line item still outstanding

        service.reviewSplit(reportId, splitIdFor(OWNER_B), OWNER_B, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.PENDING_APPROVAL); // header's own line item review still pending

        service.reviewLineItem(reportId, lineItemId, HEADER_OWNER, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.APPROVED);
    }

    @Test
    void reviewSplit_needsCorrection_doesNotBlockADifferentOwnersAbilityToReviewTheirOwnSplit() {
        submittedSplitReport("600", "400");

        service.reviewSplit(reportId, splitIdFor(OWNER_A), OWNER_A,
                new LineItemReviewRequest(LineItemReviewStatus.NEEDS_CORRECTION, "wrong cost center"));

        assertThat(assignmentFor(OWNER_A).getStatus()).isEqualTo(AssignmentStatus.ACTIVE); // not force-completed by a rejection

        // Owner B must still be able to act on their own split even though the report is now
        // AWAITING_CORRECTION because of owner A's unrelated rejection (the "don't block another
        // approver's action" rule).
        ExpenseReportResponse response = service.reviewSplit(reportId, splitIdFor(OWNER_B), OWNER_B,
                new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));

        assertThat(response).isNotNull();
        assertThat(assignmentFor(OWNER_B).getStatus()).isEqualTo(AssignmentStatus.COMPLETED);
    }

    @Test
    void reviewSplit_throwsAccessDenied_whenActorIsNotThatSplitsOwner() {
        submittedSplitReport("600", "400");

        assertThatThrownBy(() -> service.reviewSplit(reportId, splitIdFor(OWNER_A), OWNER_B,
                new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void reviewSplit_rejectsAlreadyReviewedSplit() {
        // Owner A needs a SECOND outstanding split so their combined assignment stays ACTIVE after
        // the first split is approved - otherwise the assignment completes immediately and the
        // second attempt hits "not an active approver" first, never reaching the re-review check.
        ExpenseReport report = draftReport();
        ExpenseLineItem lineItem = report.getExpenseLineItems().get(0);
        ExpenseSplit splitA1 = split(lineItem, costCenterOwnedBy(OWNER_A), "300", 1);
        ExpenseSplit splitA2 = split(lineItem, costCenterOwnedBy(OWNER_A), "300", 2);
        ExpenseSplit splitB = split(lineItem, costCenterOwnedBy(OWNER_B), "400", 3);
        lineItem.setExpenseSplits(List.of(splitA1, splitA2, splitB));
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(costCenterOwnerOnlyFlow(LevelQuorum.ANY_OF));
        when(approverSourceResolver.resolve(any(), any())).thenReturn(Optional.of(HEADER_OWNER));
        service.submit(reportId);

        UUID firstSplitId = splitA1.getSplitId();
        service.reviewSplit(reportId, firstSplitId, OWNER_A, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        assertThat(assignmentFor(OWNER_A).getStatus()).isEqualTo(AssignmentStatus.ACTIVE); // splitA2 still pending

        assertThatThrownBy(() -> service.reviewSplit(reportId, firstSplitId, OWNER_A,
                new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already been reviewed");
    }

    @Test
    void reviewSplit_requiresComment_whenFlaggingNeedsCorrection() {
        submittedSplitReport("600", "400");

        assertThatThrownBy(() -> service.reviewSplit(reportId, splitIdFor(OWNER_A), OWNER_A,
                new LineItemReviewRequest(LineItemReviewStatus.NEEDS_CORRECTION, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("comment is required");
    }

    @Test
    void isEntireInstanceDone_waivesASkippedSplitOwnerAssignmentsOwnSplits() {
        ExpenseReport report = submittedSplitReport("600", "400");

        // Simulate what ChainCorrectnessServiceImpl's (unmodified) duplicate-approver pass would have
        // done had it run for real in this mocked test - owner A's combined assignment auto-skipped
        // because they already appear earlier in the chain. Their split review is left PENDING
        // forever (nobody can act on a SKIPPED assignment's task), so it must be waived, not block.
        assignmentFor(OWNER_A).setStatus(AssignmentStatus.SKIPPED);

        service.reviewSplit(reportId, splitIdFor(OWNER_B), OWNER_B, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        service.reviewLineItem(reportId, lineItemId, HEADER_OWNER, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));

        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.APPROVED);
    }

    @Test
    void submit_normalReportWithNoSplits_resolvesOnlyTheSingleHeaderCostCenterOwnerAssignment_unchangedBehavior() {
        ExpenseReport report = draftReport(); // no splits on the line item at all
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(costCenterOwnerOnlyFlow(LevelQuorum.ANY_OF));
        when(approverSourceResolver.resolve(any(), any())).thenReturn(Optional.of(HEADER_OWNER));

        service.submit(reportId);

        assertThat(savedAssignments).hasSize(1);
        assertThat(savedAssignments.get(0).getApproverId()).isEqualTo(HEADER_OWNER);
        assertThat(savedSplitReviews).isEmpty();
    }

    // ---------------------------------------------------------------------
    // Budget encumbrance wiring (Phase 5) - creation on submit/full-restart, release on
    // recall/cancel/reject/the old cycle of a full restart. resumeInPlace touches neither.
    // ---------------------------------------------------------------------

    @Test
    void submit_validatesAndEncumbersTheNewCycle_beforeMaterializingTheChain() {
        submittedReport();

        verify(budgetEncumbranceService).validateAndEncumber(any(ExpenseReport.class), eq(1));
    }

    @Test
    void submit_propagatesInsufficientBudgetFailure_andCreatesNoApprovalChainAtAll() {
        ExpenseReport report = draftReport();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(singleLevelFlow());
        when(budgetEncumbranceService.validateAndEncumber(any(), anyInt()))
                .thenThrow(new IllegalArgumentException("Insufficient budget"));

        assertThatThrownBy(() -> service.submit(reportId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient budget");

        assertThat(savedInstances).isEmpty();
        assertThat(savedAssignments).isEmpty();
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.DRAFT); // never even reached PENDING_APPROVAL
    }

    @Test
    void recall_releasesActiveEncumbrancesForTheCurrentCycle() {
        submittedReport();

        service.recall(reportId, submitterId);

        verify(budgetEncumbranceService).releaseActiveForCycle(reportId, 1);
    }

    @Test
    void cancel_releasesActiveEncumbrancesForTheCurrentCycle() {
        submittedReport();

        service.cancel(reportId, submitterId);

        verify(budgetEncumbranceService).releaseActiveForCycle(reportId, 1);
    }

    @Test
    void rejectReport_releasesActiveEncumbrancesForTheCurrentCycle() {
        submittedReport();

        service.rejectReport(reportId, approverId, new RejectReportRequest("Duplicate submission"));

        verify(budgetEncumbranceService).releaseActiveForCycle(reportId, 1);
    }

    @Test
    void resubmit_resumesInPlace_neitherReleasesNorReEncumbers() {
        ExpenseReport report = submittedReport(); // cycle 1 already validated+encumbered once
        service.reviewLineItem(reportId, lineItemId, approverId, new LineItemReviewRequest(LineItemReviewStatus.NEEDS_CORRECTION, "fix it"));
        report.setReportStatus(ReportStatus.AWAITING_CORRECTION);
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(singleLevelFlow());

        service.submit(reportId);

        verify(budgetEncumbranceService, never()).releaseActiveForCycle(any(), anyInt());
        verify(budgetEncumbranceService, org.mockito.Mockito.times(1)).validateAndEncumber(any(), anyInt()); // still just the original cycle-1 call
    }

    @Test
    void resubmit_fullyRestarts_releasesOldCycleBeforeEncumberingTheNewOne() {
        ExpenseReport report = submittedReport(); // cycle 1
        service.reviewLineItem(reportId, lineItemId, approverId, new LineItemReviewRequest(LineItemReviewStatus.NEEDS_CORRECTION, "fix it"));
        report.setReportStatus(ReportStatus.AWAITING_CORRECTION);
        ApprovalFlow differentFlow = singleLevelFlow();
        differentFlow.setFlowId(UUID.randomUUID());
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(differentFlow);

        service.submit(reportId);

        var inOrder = org.mockito.Mockito.inOrder(budgetEncumbranceService);
        inOrder.verify(budgetEncumbranceService).releaseActiveForCycle(reportId, 1);
        inOrder.verify(budgetEncumbranceService).validateAndEncumber(any(ExpenseReport.class), eq(2));
    }

    // ---------------------------------------------------------------------
    // Existing feature integration for splits: queue, review-status, bulk approve (Phase 7)
    // ---------------------------------------------------------------------

    @Test
    void getMyQueue_forASplitOwner_showsOnlyTheirOwnPendingSplits_notADifferentOwners() {
        submittedSplitReport("600", "400");

        var queue = service.getMyQueue(OWNER_A, PageRequest.of(0, 20));

        assertThat(queue.content()).hasSize(1);
        var item = queue.content().get(0);
        assertThat(item.pendingSplits()).hasSize(1);
        assertThat(item.pendingSplits().get(0).splitId()).isEqualTo(splitIdFor(OWNER_A));
        assertThat(item.pendingSplits().get(0).allocatedAmount()).isEqualByComparingTo("600");
    }

    @Test
    void getMyQueue_forADifferentOwner_neverShowsAnotherOwnersSplits() {
        submittedSplitReport("600", "400");

        var queue = service.getMyQueue(OWNER_B, PageRequest.of(0, 20));

        var item = queue.content().get(0);
        assertThat(item.pendingSplits()).hasSize(1);
        assertThat(item.pendingSplits().get(0).splitId()).isEqualTo(splitIdFor(OWNER_B));
    }

    /**
     * Regression test (bug report: "split approval is visible in Engineering owner's queue but not
     * in HR owner's queue" - real report {@code 0458a800-246b-48a3-b759-4de2ce6ea743}) for one line
     * item split across two Cost Centers with two different owners. Root-cause finding: {@code
     * getMyQueue}/{@code matchingAssignments}/{@code createSplitOwnerAssignments} were traced
     * end-to-end and found correct - confirmed here and by the two tests above, which already prove
     * each owner independently sees their own split via a flow with a COST_CENTER_OWNER level entry.
     * The reported report's real issue was that its matched flow's Level 1 had ONLY a
     * REPORTING_MANAGER entry and zero COST_CENTER_OWNER entries, so {@code
     * createSplitOwnerAssignments}'s {@code hasCostCenterOwnerEntry} gate never fired for either
     * split - the Engineering owner's apparent success was a coincidental Reporting-Manager identity
     * overlap (a whole-line-item {@code pendingLineItems} entry, not a genuine {@code pendingSplits}
     * one), not the split-approval mechanism actually running. No code change was warranted; this
     * test asserts BOTH owners see their OWN split, from the SAME submitted report, in one place -
     * guarding the code path itself against ever regressing once a flow is correctly configured.
     */
    @Test
    void getMyQueue_bothCostCenterOwners_seeTheirOwnSplitFromTheSameOneLineItemReport() {
        submittedSplitReport("5000", "5000");

        var ownerAQueue = service.getMyQueue(OWNER_A, PageRequest.of(0, 20));
        var ownerBQueue = service.getMyQueue(OWNER_B, PageRequest.of(0, 20));

        assertThat(ownerAQueue.content()).as("Owner A (first Cost Center) must see the report in their queue").hasSize(1);
        assertThat(ownerBQueue.content()).as("Owner B (second Cost Center) must ALSO see the report in their queue").hasSize(1);

        var ownerAItem = ownerAQueue.content().get(0);
        var ownerBItem = ownerBQueue.content().get(0);
        assertThat(ownerAItem.reportId()).isEqualTo(ownerBItem.reportId()); // same report, both owners

        assertThat(ownerAItem.pendingSplits()).hasSize(1);
        assertThat(ownerAItem.pendingSplits().get(0).splitId()).isEqualTo(splitIdFor(OWNER_A));
        assertThat(ownerAItem.pendingSplits().get(0).allocatedAmount()).isEqualByComparingTo("5000");

        assertThat(ownerBItem.pendingSplits()).hasSize(1);
        assertThat(ownerBItem.pendingSplits().get(0).splitId()).isEqualTo(splitIdFor(OWNER_B));
        assertThat(ownerBItem.pendingSplits().get(0).allocatedAmount()).isEqualByComparingTo("5000");
    }

    @Test
    void getSplitReviews_returnsCurrentCycleStatus_forEverySplitAcrossAllOwners() {
        submittedSplitReport("600", "400");

        var reviews = service.getSplitReviews(reportId, submitterId);

        assertThat(reviews).hasSize(2).allMatch(r -> r.status() == LineItemReviewStatus.PENDING);

        service.reviewSplit(reportId, splitIdFor(OWNER_A), OWNER_A, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        var afterApproval = service.getSplitReviews(reportId, submitterId);
        var ownerAReview = afterApproval.stream().filter(r -> r.splitId().equals(splitIdFor(OWNER_A))).findFirst().orElseThrow();
        var ownerBReview = afterApproval.stream().filter(r -> r.splitId().equals(splitIdFor(OWNER_B))).findFirst().orElseThrow();
        assertThat(ownerAReview.status()).isEqualTo(LineItemReviewStatus.APPROVED);
        assertThat(ownerBReview.status()).isEqualTo(LineItemReviewStatus.PENDING);
    }

    @Test
    void bulkApprove_alsoApprovesTheCallersOwnPendingSplits_butNeverADifferentOwners() {
        submittedSplitReport("600", "400");

        service.bulkApprove(reportId, OWNER_A);

        assertThat(assignmentFor(OWNER_A).getStatus()).isEqualTo(AssignmentStatus.COMPLETED);
        assertThat(assignmentFor(OWNER_A).getSplitReviews().get(0).getStatus()).isEqualTo(LineItemReviewStatus.APPROVED);
        // Owner B's own split is untouched - bulk-approving as owner A must never reach into it.
        assertThat(assignmentFor(OWNER_B).getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(assignmentFor(OWNER_B).getSplitReviews().get(0).getStatus()).isEqualTo(LineItemReviewStatus.PENDING);
    }

    // ---------------------------------------------------------------------
    // Dual-role queue and bulk approve (production-readiness audit, Part 4): a caller who is BOTH
    // the normal-track approver AND a split-owner on the same report at the same level.
    // ---------------------------------------------------------------------

    /**
     * A single ANY_OF, COST_CENTER_OWNER-only level - deliberately NOT the SEQUENTIAL NAMED_USER
     * fixture, which would cascade into a SEQUENTIAL-advance-to-the-header-entry interaction
     * unrelated to what this is testing. {@code approverId} is both the report's HEADER Cost Center
     * owner (resolved via the entries loop - the "normal track") AND the split's own Cost Center
     * owner (resolved via createSplitOwnerAssignments - the "split track") - two separate
     * ApprovalAssignment rows for the same person, at the same level, active simultaneously.
     */
    private ExpenseReport submittedDualRoleReport() {
        ExpenseReport report = draftReport();
        ExpenseLineItem lineItem = report.getExpenseLineItems().get(0);
        ExpenseSplit split = split(lineItem, costCenterOwnedBy(approverId), "1000", 1);
        lineItem.setExpenseSplits(List.of(split));
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(costCenterOwnerOnlyFlow(LevelQuorum.ANY_OF));
        when(approverSourceResolver.resolve(any(), any())).thenReturn(Optional.of(approverId));
        service.submit(reportId);
        return report;
    }

    @Test
    void getMyQueue_forADualRoleCaller_returnsBothPendingLineItemsAndPendingSplitsInOneRow() {
        submittedDualRoleReport();

        var queue = service.getMyQueue(approverId, PageRequest.of(0, 20));

        assertThat(queue.content()).hasSize(1); // one row for the one report, not split across two
        var item = queue.content().get(0);
        assertThat(item.pendingLineItems()).hasSize(1); // the shared line-item review (their NAMED_USER role)
        assertThat(item.pendingSplits()).hasSize(1); // their own split (their Cost Center Owner role)
    }

    @Test
    void getMyQueue_forADifferentCaller_neverSeesTheDualRolePersonsResponsibilities() {
        submittedDualRoleReport();

        var queue = service.getMyQueue("someone-else-entirely", PageRequest.of(0, 20));

        assertThat(queue.content()).isEmpty();
    }

    @Test
    void bulkApprove_withDualRole_approvesBothTheNormalLineItemAndTheOwnSplit() {
        submittedDualRoleReport();

        service.bulkApprove(reportId, approverId);

        assertThat(savedReviews).allMatch(r -> r.getStatus() == LineItemReviewStatus.APPROVED);
        assertThat(savedSplitReviews).allMatch(r -> r.getStatus() == LineItemReviewStatus.APPROVED);
    }

    @Test
    void bulkApprove_completesNormalApproval_whileSplitRemainsPending_ifOnlyLineItemIsApprovedIndividually() {
        submittedDualRoleReport();

        service.reviewLineItem(reportId, lineItemId, approverId, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));

        assertThat(savedReviews).allMatch(r -> r.getStatus() == LineItemReviewStatus.APPROVED);
        assertThat(savedSplitReviews).allMatch(r -> r.getStatus() == LineItemReviewStatus.PENDING);
    }
}
