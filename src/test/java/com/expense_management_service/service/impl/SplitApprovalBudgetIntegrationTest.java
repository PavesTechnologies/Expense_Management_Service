package com.expense_management_service.service.impl;

import com.expense_management_service.dto.request.LineItemReviewRequest;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.entity.ApprovalAssignment;
import com.expense_management_service.entity.ApprovalFlow;
import com.expense_management_service.entity.ApprovalLevel;
import com.expense_management_service.entity.ApprovalLevelApprover;
import com.expense_management_service.entity.ApprovalLevelInstance;
import com.expense_management_service.entity.ApprovalLineItemReview;
import com.expense_management_service.entity.ApprovalSplitReview;
import com.expense_management_service.entity.BudgetEncumbrance;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.CostCenterBudget;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.ExpenseSplit;
import com.expense_management_service.enums.ApproverSourceType;
import com.expense_management_service.enums.AssignmentStatus;
import com.expense_management_service.enums.BudgetEncumbranceStatus;
import com.expense_management_service.enums.LevelInstanceStatus;
import com.expense_management_service.enums.LevelQuorum;
import com.expense_management_service.enums.LevelType;
import com.expense_management_service.enums.LineItemReviewStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.enums.SplitType;
import com.expense_management_service.mapper.BudgetEncumbranceMapper;
import com.expense_management_service.mapper.ExpenseReportMapper;
import com.expense_management_service.mapper.PolicyViolationMapper;
import com.expense_management_service.repository.ApprovalAssignmentRepository;
import com.expense_management_service.repository.ApprovalLevelInstanceRepository;
import com.expense_management_service.repository.ApprovalLineItemReviewRepository;
import com.expense_management_service.repository.ApprovalSplitReviewRepository;
import com.expense_management_service.repository.BudgetEncumbranceRepository;
import com.expense_management_service.repository.CostCenterBudgetRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.PolicyViolationRepository;
import com.expense_management_service.repository.SystemConfigurationRepository;
import com.expense_management_service.service.ApprovalEventPublisher;
import com.expense_management_service.service.ApprovalFlowResolutionService;
import com.expense_management_service.service.ApproverSourceResolver;
import com.expense_management_service.service.CostCenterBudgetService;
import com.expense_management_service.service.DelegationService;
import com.expense_management_service.service.MaterialChangeEvaluator;
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
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Phase 8: cross-cutting integration coverage that no single phase's unit tests can catch, because
 * each of those mocks out the OTHER real collaborators. This wires the REAL {@code
 * ApprovalWorkflowServiceImpl}, {@code ChainCorrectnessServiceImpl}, and {@code
 * BudgetEncumbranceServiceImpl} together (repositories still mocked, backed by simple in-memory
 * lists - no database) and exercises the interactions between split-aware approval routing, chain
 * correctness (self-approval/duplicate-skip), and budget encumbrance that the per-class unit test
 * suites for Phases 1-7 necessarily could not, since each of those substituted a mock for the
 * other two real classes. This is a new pattern for this codebase (every other test is either a
 * pure-mock unit test or a {@code @WebMvcTest} slice) - added deliberately narrow (five scenarios),
 * not a general-purpose integration harness.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class SplitApprovalBudgetIntegrationTest {

    @Mock private ExpenseReportRepository expenseReportRepository;
    @Mock private ApprovalLevelInstanceRepository approvalLevelInstanceRepository;
    @Mock private ApprovalAssignmentRepository approvalAssignmentRepository;
    @Mock private ApprovalLineItemReviewRepository approvalLineItemReviewRepository;
    @Mock private ApprovalSplitReviewRepository approvalSplitReviewRepository;
    @Mock private PolicyViolationRepository policyViolationRepository;
    @Mock private ApprovalFlowResolutionService approvalFlowResolutionService;
    @Mock private ApproverSourceResolver approverSourceResolver;
    @Mock private DelegationService delegationService;
    @Mock private PolicyEvaluationGateway policyEvaluationGateway;
    @Mock private ApprovalEventPublisher approvalEventPublisher;
    @Mock private SlaPolicyService slaPolicyService;
    @Mock private MaterialChangeEvaluator materialChangeEvaluator;
    @Mock private EmployeeCacheRepository employeeCacheRepository;
    @Mock private SystemConfigurationRepository systemConfigurationRepository;
    @Mock private BudgetEncumbranceRepository budgetEncumbranceRepository;
    @Mock private CostCenterBudgetRepository costCenterBudgetRepository;
    @Mock private CostCenterBudgetService costCenterBudgetService;

    private ApprovalWorkflowServiceImpl service;

    private final List<ApprovalLevelInstance> savedInstances = new ArrayList<>();
    private final List<ApprovalAssignment> savedAssignments = new ArrayList<>();
    private final List<ApprovalLineItemReview> savedReviews = new ArrayList<>();
    private final List<ApprovalSplitReview> savedSplitReviews = new ArrayList<>();
    private final List<BudgetEncumbrance> savedEncumbrances = new ArrayList<>();

    private final UUID reportId = UUID.randomUUID();
    private final UUID flowId = UUID.randomUUID();
    private final UUID lineItemId = UUID.randomUUID();
    private final String submitterId = "5100001";

    @BeforeEach
    void setUp() {
        var chainCorrectnessService = new ChainCorrectnessServiceImpl(
                approvalLevelInstanceRepository, approvalAssignmentRepository,
                employeeCacheRepository, systemConfigurationRepository, delegationService);
        var budgetEncumbranceService = new BudgetEncumbranceServiceImpl(
                budgetEncumbranceRepository, costCenterBudgetRepository, costCenterBudgetService,
                new BudgetEncumbranceMapper());

        service = new ApprovalWorkflowServiceImpl(expenseReportRepository, approvalLevelInstanceRepository,
                approvalAssignmentRepository, approvalLineItemReviewRepository, approvalSplitReviewRepository,
                policyViolationRepository, approvalFlowResolutionService, approverSourceResolver,
                chainCorrectnessService, budgetEncumbranceService, delegationService, policyEvaluationGateway,
                approvalEventPublisher, slaPolicyService, new PolicyViolationMapper(),
                List.of(new ApprovalReviewStrategy(approvalLineItemReviewRepository)),
                new ExpenseReportResponseFactory(new ExpenseReportMapper(), policyViolationRepository),
                materialChangeEvaluator);

        when(policyEvaluationGateway.evaluate(any())).thenReturn(new PolicyDecision(true, List.of()));
        when(policyViolationRepository.findByLineItem_Report_ReportId(any())).thenReturn(List.of());
        when(slaPolicyService.resolveSlaBusinessDays()).thenReturn(3);
        when(materialChangeEvaluator.computeGlAccountFingerprint(any())).thenReturn("");
        when(delegationService.canAct(any(), any())).thenAnswer(inv -> inv.getArgument(0).equals(inv.getArgument(1)));
        when(delegationService.resolveActiveDelegate(any())).thenReturn(Optional.empty());
        when(delegationService.resolveApproverIdsActingFor(any()))
                .thenAnswer(inv -> java.util.Set.of((String) inv.getArgument(0)));

        when(approvalLevelInstanceRepository.save(any(ApprovalLevelInstance.class))).thenAnswer(inv -> {
            ApprovalLevelInstance i = inv.getArgument(0);
            if (i.getInstanceId() == null) i.setInstanceId(UUID.randomUUID());
            if (i.getCreatedAt() == null) i.setCreatedAt(LocalDateTime.now()); // simulates @CreationTimestamp
            savedInstances.removeIf(existing -> existing.getInstanceId().equals(i.getInstanceId()));
            savedInstances.add(i);
            return i;
        });
        when(approvalAssignmentRepository.save(any(ApprovalAssignment.class))).thenAnswer(inv -> {
            ApprovalAssignment a = inv.getArgument(0);
            if (a.getAssignmentId() == null) a.setAssignmentId(UUID.randomUUID());
            savedAssignments.removeIf(existing -> existing.getAssignmentId().equals(a.getAssignmentId()));
            savedAssignments.add(a);
            return a;
        });
        when(approvalLineItemReviewRepository.save(any(ApprovalLineItemReview.class))).thenAnswer(inv -> {
            ApprovalLineItemReview r = inv.getArgument(0);
            if (r.getReviewId() == null) r.setReviewId(UUID.randomUUID());
            savedReviews.removeIf(existing -> existing.getReviewId().equals(r.getReviewId()));
            savedReviews.add(r);
            return r;
        });
        when(approvalSplitReviewRepository.save(any(ApprovalSplitReview.class))).thenAnswer(inv -> {
            ApprovalSplitReview r = inv.getArgument(0);
            if (r.getReviewId() == null) r.setReviewId(UUID.randomUUID());
            savedSplitReviews.removeIf(existing -> existing.getReviewId().equals(r.getReviewId()));
            savedSplitReviews.add(r);
            return r;
        });
        when(expenseReportRepository.save(any(ExpenseReport.class))).thenAnswer(inv -> inv.getArgument(0));

        when(approvalAssignmentRepository.findByLevelInstance_InstanceId(any()))
                .thenAnswer(inv -> savedAssignments.stream()
                        .filter(a -> a.getLevelInstance().getInstanceId().equals(inv.getArgument(0))).toList());
        when(approvalAssignmentRepository.findByLevelInstance_Report_ReportId(any()))
                .thenAnswer(inv -> savedAssignments.stream()
                        .filter(a -> a.getLevelInstance().getReport().getReportId().equals(inv.getArgument(0))).toList());
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
        when(approvalSplitReviewRepository.findByAssignment_AssignmentId(any()))
                .thenAnswer(inv -> savedSplitReviews.stream()
                        .filter(r -> r.getAssignment().getAssignmentId().equals(inv.getArgument(0))).toList());
        when(approvalSplitReviewRepository.findBySplit_SplitIdAndAssignment_AssignmentId(any(), any()))
                .thenAnswer(inv -> savedSplitReviews.stream()
                        .filter(r -> r.getSplit().getSplitId().equals(inv.getArgument(0)))
                        .filter(r -> r.getAssignment().getAssignmentId().equals(inv.getArgument(1)))
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
        when(approvalAssignmentRepository.findDistinctReportIdsByStatusAndApproverIdIn(any(), any(), any()))
                .thenAnswer(inv -> {
                    var status = (com.expense_management_service.enums.AssignmentStatus) inv.getArgument(0);
                    @SuppressWarnings("unchecked")
                    var approverIds = (java.util.Collection<String>) inv.getArgument(1);
                    var pageable = (org.springframework.data.domain.Pageable) inv.getArgument(2);
                    var reportIds = savedAssignments.stream()
                            .filter(a -> a.getStatus() == status && approverIds.contains(a.getApproverId()))
                            .map(a -> a.getLevelInstance().getReport().getReportId())
                            .distinct().toList();
                    return new org.springframework.data.domain.PageImpl<>(reportIds, pageable, reportIds.size());
                });

        when(budgetEncumbranceRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<BudgetEncumbrance> list = inv.getArgument(0);
            for (BudgetEncumbrance e : list) {
                if (e.getEncumbranceId() == null) e.setEncumbranceId(UUID.randomUUID());
                savedEncumbrances.removeIf(existing -> existing.getEncumbranceId().equals(e.getEncumbranceId()));
                savedEncumbrances.add(e);
            }
            return list;
        });
        when(budgetEncumbranceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus(any(), any(), any()))
                .thenAnswer(inv -> savedEncumbrances.stream()
                        .filter(e -> e.getReport().getReportId().equals(inv.getArgument(0)))
                        .filter(e -> e.getSubmissionCycle().equals(inv.getArgument(1)))
                        .filter(e -> e.getStatus() == inv.getArgument(2))
                        .toList());
        when(budgetEncumbranceRepository.sumAmountByBudget_BudgetIdAndStatus(any(), any()))
                .thenAnswer(inv -> savedEncumbrances.stream()
                        .filter(e -> e.getBudget() != null && e.getBudget().getBudgetId().equals(inv.getArgument(0)))
                        .filter(e -> e.getStatus() == inv.getArgument(1))
                        .map(BudgetEncumbrance::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        // Bug 7 (production-readiness audit, real-DB concurrency testing): effectiveAvailable now reads
        // via this locking query instead of the aggregate above - see BudgetEncumbranceRepository's javadoc.
        when(budgetEncumbranceRepository.findForUpdateByBudget_BudgetIdAndStatus(any(), any()))
                .thenAnswer(inv -> savedEncumbrances.stream()
                        .filter(e -> e.getBudget() != null && e.getBudget().getBudgetId().equals(inv.getArgument(0)))
                        .filter(e -> e.getStatus() == inv.getArgument(1))
                        .toList());
    }

    // ---------------------------------------------------------------------
    // fixtures
    // ---------------------------------------------------------------------

    private CostCenter costCenterOwnedBy(String ownerEmployeeId, boolean allowUnbudgeted) {
        return CostCenter.builder().costCenterId(UUID.randomUUID()).costCenterCode("CC-" + ownerEmployeeId)
                .ownerEmployeeId(ownerEmployeeId).allowUnbudgeted(allowUnbudgeted).build();
    }

    private ExpenseSplit split(ExpenseLineItem lineItem, CostCenter costCenter, String amount, int order) {
        return ExpenseSplit.builder().splitId(UUID.randomUUID()).lineItem(lineItem).costCenter(costCenter)
                .splitType(SplitType.FIXED_AMOUNT).allocatedAmount(new BigDecimal(amount)).splitOrder(order).build();
    }

    private CostCenterBudget budgetWith(CostCenter costCenter, String availableBudget) {
        return CostCenterBudget.builder().budgetId(UUID.randomUUID()).costCenter(costCenter).fiscalYear("FY2026")
                .availableBudget(new BigDecimal(availableBudget)).build();
    }

    private void stubBudget(CostCenter costCenter, CostCenterBudget budget) {
        when(costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(
                costCenter.getCostCenterId(), "FY2026")).thenReturn(Optional.of(budget));
    }

    /** One APPROVAL level, single COST_CENTER_OWNER entry - split-owner assignments resolve alongside it (Phase 4). */
    private ApprovalFlow costCenterOwnerOnlyFlow(String headerOwnerId) {
        ApprovalFlow flow = ApprovalFlow.builder().flowId(flowId).name("Cost Center Owner").isCatchAll(false).build();
        ApprovalLevel level = ApprovalLevel.builder().levelId(UUID.randomUUID()).flow(flow).levelOrder(1)
                .quorum(LevelQuorum.ANY_OF).levelType(LevelType.APPROVAL).build();
        level.getApprovers().add(ApprovalLevelApprover.builder().entryId(UUID.randomUUID()).level(level)
                .sourceType(ApproverSourceType.COST_CENTER_OWNER).build());
        flow.getLevels().add(level);
        when(approverSourceResolver.resolve(any(), any())).thenReturn(Optional.of(headerOwnerId));
        return flow;
    }

    private ApprovalAssignment assignmentFor(String ownerEmployeeId) {
        return savedAssignments.stream().filter(a -> a.getApproverId().equals(ownerEmployeeId)).findFirst().orElseThrow();
    }

    /** LEVEL 1: COST_CENTER_OWNER only (split owners). LEVEL 2: a manager NAMED_USER entry - for proving Level 2 cannot activate until every Level 1 responsibility (line items AND every split owner) completes. */
    private ApprovalFlow costCenterOwnerThenManagerFlow(String headerOwnerId, String managerId) {
        ApprovalFlow flow = ApprovalFlow.builder().flowId(flowId).name("Cost Center Owner then Manager").isCatchAll(false).build();
        ApprovalLevel level1 = ApprovalLevel.builder().levelId(UUID.randomUUID()).flow(flow).levelOrder(1)
                .quorum(LevelQuorum.ANY_OF).levelType(LevelType.APPROVAL).build();
        level1.getApprovers().add(ApprovalLevelApprover.builder().entryId(UUID.randomUUID()).level(level1)
                .sourceType(ApproverSourceType.COST_CENTER_OWNER).build());
        ApprovalLevel level2 = ApprovalLevel.builder().levelId(UUID.randomUUID()).flow(flow).levelOrder(2)
                .quorum(LevelQuorum.ANY_OF).levelType(LevelType.APPROVAL).build();
        level2.getApprovers().add(ApprovalLevelApprover.builder().entryId(UUID.randomUUID()).level(level2)
                .entryOrder(1).sourceType(ApproverSourceType.NAMED_USER).sourceReference(managerId).build());
        flow.getLevels().add(level1);
        flow.getLevels().add(level2);
        when(approverSourceResolver.resolve(any(), any())).thenAnswer(inv -> {
            ApprovalLevelApprover entry = inv.getArgument(0);
            return entry.getSourceType() == ApproverSourceType.NAMED_USER
                    ? Optional.of(entry.getSourceReference()) : Optional.of(headerOwnerId);
        });
        return flow;
    }

    /**
     * LEVEL 1: a single normal-track entry (standing in for Reporting Manager) resolving directly to
     * {@code level1ApproverId}. LEVEL 2: a COST_CENTER_OWNER entry (the header/normal track) alongside
     * whatever split-owner assignments the report's splits resolve to (Phase 4). LEVEL 3: a single
     * normal-track entry resolving to {@code level3ApproverId} (standing in for Finance) - reproduces
     * the exact 3-level shape from the reported bug without needing a FINANCE_VERIFICATION strategy
     * registered in this test's minimal {@code service} wiring.
     */
    private ApprovalFlow reportingManagerThenCostCenterOwnerThenFinanceFlow(String level1ApproverId, String level3ApproverId) {
        ApprovalFlow flow = ApprovalFlow.builder().flowId(flowId).name("Reporting Manager then Cost Center Owner then Finance").isCatchAll(false).build();
        ApprovalLevel level1 = ApprovalLevel.builder().levelId(UUID.randomUUID()).flow(flow).levelOrder(1)
                .quorum(LevelQuorum.ANY_OF).levelType(LevelType.APPROVAL).build();
        level1.getApprovers().add(ApprovalLevelApprover.builder().entryId(UUID.randomUUID()).level(level1)
                .entryOrder(1).sourceType(ApproverSourceType.NAMED_USER).sourceReference(level1ApproverId).build());
        ApprovalLevel level2 = ApprovalLevel.builder().levelId(UUID.randomUUID()).flow(flow).levelOrder(2)
                .quorum(LevelQuorum.ANY_OF).levelType(LevelType.APPROVAL).build();
        level2.getApprovers().add(ApprovalLevelApprover.builder().entryId(UUID.randomUUID()).level(level2)
                .sourceType(ApproverSourceType.COST_CENTER_OWNER).build());
        ApprovalLevel level3 = ApprovalLevel.builder().levelId(UUID.randomUUID()).flow(flow).levelOrder(3)
                .quorum(LevelQuorum.ANY_OF).levelType(LevelType.APPROVAL).build();
        level3.getApprovers().add(ApprovalLevelApprover.builder().entryId(UUID.randomUUID()).level(level3)
                .entryOrder(1).sourceType(ApproverSourceType.NAMED_USER).sourceReference(level3ApproverId).build());
        flow.getLevels().add(level1);
        flow.getLevels().add(level2);
        flow.getLevels().add(level3);
        when(approverSourceResolver.resolve(any(), any())).thenAnswer(inv -> {
            ApprovalLevelApprover entry = inv.getArgument(0);
            ExpenseReport report = inv.getArgument(1);
            return entry.getSourceType() == ApproverSourceType.NAMED_USER
                    ? Optional.of(entry.getSourceReference())
                    : Optional.of(report.getCostCenter().getOwnerEmployeeId());
        });
        return flow;
    }

    private int currentActiveLevelOrder() {
        return savedInstances.stream().filter(i -> i.getStatus() == LevelInstanceStatus.ACTIVE)
                .findFirst().orElseThrow(() -> new AssertionError("No ACTIVE level instance")).getLevelOrder();
    }

    /** Builds a DRAFT report with one, as-yet-unsplit line item - the caller attaches ExpenseSplit rows afterward (they must reference the returned report's line item, which cannot exist before this call). */
    private ExpenseReport draftReportWithSplits(String headerOwnerId) {
        CostCenter headerCostCenter = costCenterOwnedBy(headerOwnerId, false);
        ExpenseLineItem lineItem = ExpenseLineItem.builder().lineItemId(lineItemId)
                .amount(new BigDecimal("1000")).baseAmount(new BigDecimal("1000")).expenseSplits(List.of()).build();
        ExpenseReport report = ExpenseReport.builder().reportId(reportId).employeeId(submitterId)
                .reportStatus(ReportStatus.DRAFT).costCenter(headerCostCenter).fiscalYear("FY2026")
                .totalAmount(new BigDecimal("1000")).expenseLineItems(List.of(lineItem)).build();
        lineItem.setReport(report);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        return report;
    }

    // ---------------------------------------------------------------------
    // 1. Full split lifecycle end to end
    // ---------------------------------------------------------------------

    @Test
    void fullSplitLifecycle_bothOwnersAndHeaderApprove_reportReachesApproved_encumbrancesStayActive() {
        String ownerA = "5100061";
        String ownerB = "5100062";
        String headerOwner = "5100060";
        CostCenter ccA = costCenterOwnedBy(ownerA, false);
        CostCenter ccB = costCenterOwnedBy(ownerB, false);
        ExpenseReport report = draftReportWithSplits(headerOwner);
        ExpenseLineItem lineItem = report.getExpenseLineItems().get(0);
        ExpenseSplit splitA = split(lineItem, ccA, "600", 1);
        ExpenseSplit splitB = split(lineItem, ccB, "400", 2);
        lineItem.setExpenseSplits(List.of(splitA, splitB));
        ApprovalFlow flow = costCenterOwnerOnlyFlow(headerOwner);
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(flow);
        stubBudget(ccA, budgetWith(ccA, "20000"));
        stubBudget(ccB, budgetWith(ccB, "20000"));

        service.submit(reportId);

        // Budget was validated and encumbered BEFORE the chain was even materialized (Phase 5/6).
        assertThat(savedEncumbrances).hasSize(2).allMatch(e -> e.getStatus() == BudgetEncumbranceStatus.ACTIVE);

        service.reviewSplit(reportId, splitA.getSplitId(), ownerA, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.PENDING_APPROVAL);

        service.reviewSplit(reportId, splitB.getSplitId(), ownerB, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.PENDING_APPROVAL); // header's own line item still pending

        service.reviewLineItem(reportId, lineItemId, headerOwner, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));

        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.APPROVED);
        // Encumbrances remain ACTIVE all the way to APPROVED - consumption only happens at AP payment (Phase 6).
        assertThat(savedEncumbrances).allMatch(e -> e.getStatus() == BudgetEncumbranceStatus.ACTIVE);
    }

    // ---------------------------------------------------------------------
    // 2. Self-approval redirect (real ChainCorrectnessServiceImpl) on a split-owner assignment
    // ---------------------------------------------------------------------

    @Test
    void selfApproval_redirectsASplitOwnerAssignment_whenSubmitterIsTheirOwnCostCenterOwner() {
        String managerId = "5100070";
        CostCenter ccOwnedBySubmitter = costCenterOwnedBy(submitterId, false); // submitter owns this cost center
        ExpenseReport report = draftReportWithSplits("5100060");
        ExpenseLineItem lineItem = report.getExpenseLineItems().get(0);
        ExpenseSplit selfOwnedSplit = split(lineItem, ccOwnedBySubmitter, "1000", 1);
        lineItem.setExpenseSplits(List.of(selfOwnedSplit));
        ApprovalFlow flow = costCenterOwnerOnlyFlow("5100060");
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(flow);
        stubBudget(ccOwnedBySubmitter, budgetWith(ccOwnedBySubmitter, "20000"));
        when(employeeCacheRepository.findByEmployeeId(submitterId))
                .thenReturn(Optional.of(EmployeeCache.builder().employeeId(submitterId).managerEmployeeId(managerId).build()));

        service.submit(reportId);

        // The split-owner assignment resolved to the submitter themselves - the real
        // ChainCorrectnessServiceImpl must have redirected it to their manager, exactly like it
        // already does for a normal-track assignment (this codepath is entirely generic, per
        // Phase 4's finding - no ChainCorrectnessServiceImpl change was needed).
        ApprovalAssignment splitOwnerAssignment = savedAssignments.stream()
                .filter(a -> !a.getSplitReviews().isEmpty()).findFirst().orElseThrow();
        assertThat(splitOwnerAssignment.getApproverId()).isEqualTo(managerId);
        assertThat(splitOwnerAssignment.getSupersededApproverId()).isEqualTo(submitterId);
    }

    // ---------------------------------------------------------------------
    // 3. Duplicate-approver skip waives a split-owner's splits (real ChainCorrectnessServiceImpl)
    // ---------------------------------------------------------------------

    @Test
    void duplicateApproverSkip_waivesASplitOwnersSplits_whenTheSameApproverAlreadyResolvedEarlierInTheChain() {
        String repeatedApprover = "5100080";
        CostCenter ccOwnedByRepeated = costCenterOwnedBy(repeatedApprover, false);
        ExpenseReport report = draftReportWithSplits(repeatedApprover); // header owner == split owner == same person
        ExpenseLineItem lineItem = report.getExpenseLineItems().get(0);
        ExpenseSplit onlySplit = split(lineItem, ccOwnedByRepeated, "1000", 1);
        lineItem.setExpenseSplits(List.of(onlySplit));
        ApprovalFlow flow = costCenterOwnerOnlyFlow(repeatedApprover);
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(flow);
        stubBudget(ccOwnedByRepeated, budgetWith(ccOwnedByRepeated, "20000"));

        service.submit(reportId);

        // Two assignments resolved to the SAME person at the SAME level (the header entry and the
        // split-owner entry) - the real duplicate-approver pass must have skipped the second one.
        List<ApprovalAssignment> forRepeatedApprover = savedAssignments.stream()
                .filter(a -> a.getApproverId().equals(repeatedApprover)).toList();
        assertThat(forRepeatedApprover).hasSize(2);
        long skipped = forRepeatedApprover.stream().filter(a -> a.getStatus() == AssignmentStatus.SKIPPED).count();
        assertThat(skipped).isEqualTo(1);

        // The report must still be able to reach APPROVED - completing the one non-skipped
        // assignment's line item review is enough; the skipped split-owner's own split is waived,
        // never blocking level completion (isEntireInstanceDone excludes SKIPPED assignments).
        service.reviewLineItem(reportId, lineItemId, repeatedApprover, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.APPROVED);
    }

    // ---------------------------------------------------------------------
    // 4. Insufficient budget blocks submission entirely (real BudgetEncumbranceServiceImpl)
    // ---------------------------------------------------------------------

    @Test
    void insufficientBudget_blocksSubmission_createsNoApprovalChainAndNoEncumbrance() {
        String ownerA = "5100061";
        CostCenter ccA = costCenterOwnedBy(ownerA, false);
        ExpenseReport report = draftReportWithSplits("5100060");
        ExpenseLineItem lineItem = report.getExpenseLineItems().get(0);
        ExpenseSplit splitA = split(lineItem, ccA, "1000", 1);
        lineItem.setExpenseSplits(List.of(splitA));
        ApprovalFlow flow = costCenterOwnerOnlyFlow("5100060");
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(flow);
        stubBudget(ccA, budgetWith(ccA, "500")); // insufficient for the 1000 needed

        assertThatThrownBy(() -> service.submit(reportId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient budget");

        assertThat(savedEncumbrances).isEmpty();
        assertThat(savedInstances).isEmpty();
        assertThat(savedAssignments).isEmpty();
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.DRAFT);
    }

    // ---------------------------------------------------------------------
    // 5. Mixed split + unsplit report - both tracks must complete independently
    // ---------------------------------------------------------------------

    @Test
    void mixedSplitAndUnsplitReport_bothTracksMustCompleteIndependently_beforeReportApproves() {
        String ownerA = "5100061";
        String headerOwner = "5100060";
        CostCenter ccA = costCenterOwnedBy(ownerA, false);
        CostCenter headerCostCenter = costCenterOwnedBy(headerOwner, false);
        ExpenseLineItem splitLine = ExpenseLineItem.builder().lineItemId(lineItemId)
                .amount(new BigDecimal("600")).baseAmount(new BigDecimal("600")).build();
        UUID normalLineItemId = UUID.randomUUID();
        ExpenseLineItem normalLine = ExpenseLineItem.builder().lineItemId(normalLineItemId)
                .amount(new BigDecimal("400")).baseAmount(new BigDecimal("400")).build();
        ExpenseSplit splitA = split(splitLine, ccA, "600", 1);
        splitLine.setExpenseSplits(List.of(splitA));
        normalLine.setExpenseSplits(List.of());
        ExpenseReport report = ExpenseReport.builder().reportId(reportId).employeeId(submitterId)
                .reportStatus(ReportStatus.DRAFT).costCenter(headerCostCenter).fiscalYear("FY2026")
                .totalAmount(new BigDecimal("1000")).expenseLineItems(List.of(splitLine, normalLine)).build();
        splitLine.setReport(report);
        normalLine.setReport(report);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        ApprovalFlow flow = costCenterOwnerOnlyFlow(headerOwner);
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(flow);
        stubBudget(ccA, budgetWith(ccA, "20000"));
        stubBudget(headerCostCenter, budgetWith(headerCostCenter, "20000"));

        service.submit(reportId);

        assertThat(savedEncumbrances).hasSize(2); // one for the split, one for the unsplit remainder

        // Header approves BOTH line item reviews (their own unsplit line item + the split line
        // item's shared review) - still not done, owner A's own split is untouched.
        service.reviewLineItem(reportId, normalLineItemId, headerOwner, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        service.reviewLineItem(reportId, lineItemId, headerOwner, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.PENDING_APPROVAL);

        service.reviewSplit(reportId, splitA.getSplitId(), ownerA, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.APPROVED);
    }

    // ---------------------------------------------------------------------
    // 6. Production-readiness audit, Parts 1-3: correction changes the split structure itself
    // (amount changed, a Cost Center removed, a new one added) after one owner already approved
    // and another requested correction - the exact scenario Part 2 requires end to end.
    // ---------------------------------------------------------------------

    @Test
    void splitStructureChangeDuringCorrection_invalidatesStaleApproval_addsNewOwner_waivesRemovedOwner_reconcilesBudget() {
        String headerOwner = "5100060";
        String ownerA = "5100061";
        String ownerB = "5100062";
        String ownerC = "5100063";
        CostCenter ccA = costCenterOwnedBy(ownerA, false);
        CostCenter ccB = costCenterOwnedBy(ownerB, false);
        CostCenter ccC = costCenterOwnedBy(ownerC, false);

        ExpenseReport report = draftReportWithSplits(headerOwner);
        ExpenseLineItem lineItem = report.getExpenseLineItems().get(0);
        ExpenseSplit splitA = split(lineItem, ccA, "600", 1);
        ExpenseSplit splitB = split(lineItem, ccB, "400", 2);
        lineItem.setExpenseSplits(List.of(splitA, splitB));
        ApprovalFlow flow = costCenterOwnerOnlyFlow(headerOwner);
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(flow);
        stubBudget(ccA, budgetWith(ccA, "20000"));
        stubBudget(ccB, budgetWith(ccB, "20000"));

        service.submit(reportId);
        assertThat(savedEncumbrances).hasSize(2);

        // Owner A approves their split; Owner B flags theirs as needing correction.
        service.reviewSplit(reportId, splitA.getSplitId(), ownerA, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        service.reviewSplit(reportId, splitB.getSplitId(), ownerB,
                new LineItemReviewRequest(LineItemReviewStatus.NEEDS_CORRECTION, "wrong Cost Center"));
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.AWAITING_CORRECTION);
        assertThat(assignmentFor(ownerA).getStatus()).isEqualTo(AssignmentStatus.COMPLETED);

        // Employee corrects the splits: A's amount changes (600 -> 200), B is removed entirely, a
        // brand-new Cost Center C is added at 800. This models exactly what ExpenseSplitServiceImpl's
        // reconcile-in-place would have produced: splitA is the SAME row (same splitId) with a bumped
        // updatedAt, splitB is soft-deleted (removedAt set, never physically deleted), splitC is new.
        splitA.setAllocatedAmount(new BigDecimal("200"));
        splitA.setUpdatedAt(LocalDateTime.now().plusSeconds(1));
        splitB.setRemovedAt(LocalDateTime.now());
        ExpenseSplit splitC = split(lineItem, ccC, "800", 2);
        lineItem.setExpenseSplits(List.of(splitA, splitB, splitC));
        stubBudget(ccC, budgetWith(ccC, "20000"));

        service.submit(reportId); // AWAITING_CORRECTION -> resumeInPlace (same flow still matches)

        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.PENDING_APPROVAL);

        // Owner A's stale APPROVED review must not stand for the new 200 allocation.
        ApprovalAssignment ownerAAssignment = assignmentFor(ownerA);
        assertThat(ownerAAssignment.getStatus()).isEqualTo(AssignmentStatus.ACTIVE); // reactivated from COMPLETED
        ApprovalSplitReview ownerAReview = ownerAAssignment.getSplitReviews().stream()
                .filter(r -> r.getSplit().getSplitId().equals(splitA.getSplitId())).findFirst().orElseThrow();
        assertThat(ownerAReview.getStatus()).isEqualTo(LineItemReviewStatus.PENDING);

        // Owner C is a brand-new split-owner assignment, generated correctly and already active.
        ApprovalAssignment ownerCAssignment = assignmentFor(ownerC);
        assertThat(ownerCAssignment.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(ownerCAssignment.getSplitReviews()).hasSize(1);
        assertThat(ownerCAssignment.getSplitReviews().get(0).getStatus()).isEqualTo(LineItemReviewStatus.PENDING);

        // Owner B's now-removed split must never be actionable again, and must never block the level.
        assertThatThrownBy(() -> service.reviewSplit(reportId, splitB.getSplitId(), ownerB,
                new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("removed");

        // Budget reconciles to the CORRECTED allocation (200 + 800 = 1000), not the original (600+400).
        assertThat(savedEncumbrances.stream().filter(e -> e.getStatus() == BudgetEncumbranceStatus.ACTIVE)
                .map(BudgetEncumbrance::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1000");

        // Owner A re-approves the smaller allocation, Owner C approves the new one, header approves
        // the line item - the level completes WITHOUT anyone ever touching Owner B's stale review.
        service.reviewSplit(reportId, splitA.getSplitId(), ownerA, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        service.reviewSplit(reportId, splitC.getSplitId(), ownerC, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        service.reviewLineItem(reportId, lineItemId, headerOwner, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));

        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.APPROVED);
    }

    // ---------------------------------------------------------------------
    // 7. Production-readiness audit, Part 10: multi-level approval with splits - Level 2 must never
    // activate early, before every Level 1 responsibility (shared line item AND every split owner)
    // completes.
    // ---------------------------------------------------------------------

    @Test
    void multiLevelApproval_level2NeverActivatesUntilAllLevel1SplitOwnersAndLineItemsComplete() {
        String headerOwner = "5100060";
        String ownerA = "5100061";
        String ownerB = "5100062";
        String managerId = "5100099";
        CostCenter ccA = costCenterOwnedBy(ownerA, false);
        CostCenter ccB = costCenterOwnedBy(ownerB, false);
        ExpenseReport report = draftReportWithSplits(headerOwner);
        ExpenseLineItem lineItem = report.getExpenseLineItems().get(0);
        ExpenseSplit splitA = split(lineItem, ccA, "600", 1);
        ExpenseSplit splitB = split(lineItem, ccB, "400", 2);
        lineItem.setExpenseSplits(List.of(splitA, splitB));
        ApprovalFlow flow = costCenterOwnerThenManagerFlow(headerOwner, managerId);
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(flow);
        stubBudget(ccA, budgetWith(ccA, "20000"));
        stubBudget(ccB, budgetWith(ccB, "20000"));

        service.submit(reportId);

        assertThat(savedInstances.stream().filter(i -> i.getLevelOrder() == 1).findFirst().orElseThrow().getStatus())
                .isEqualTo(LevelInstanceStatus.ACTIVE);
        assertThat(savedInstances.stream().filter(i -> i.getLevelOrder() == 2).findFirst().orElseThrow().getStatus())
                .isEqualTo(LevelInstanceStatus.QUEUED); // Level 2 must not exist as ACTIVE yet

        service.reviewLineItem(reportId, lineItemId, headerOwner, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        service.reviewSplit(reportId, splitA.getSplitId(), ownerA, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));

        // Owner B still pending - Level 2 must still not have activated.
        assertThat(savedInstances.stream().filter(i -> i.getLevelOrder() == 2).findFirst().orElseThrow().getStatus())
                .isEqualTo(LevelInstanceStatus.QUEUED);
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.PENDING_APPROVAL);

        service.reviewSplit(reportId, splitB.getSplitId(), ownerB, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));

        // Only NOW, with every Level 1 responsibility satisfied, does Level 2 activate.
        assertThat(savedInstances.stream().filter(i -> i.getLevelOrder() == 2).findFirst().orElseThrow().getStatus())
                .isEqualTo(LevelInstanceStatus.ACTIVE);
        assertThat(assignmentFor(managerId).getStatus()).isEqualTo(AssignmentStatus.ACTIVE);

        service.reviewLineItem(reportId, lineItemId, managerId, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.APPROVED);
    }

    // ---------------------------------------------------------------------
    // 8. Production-readiness audit, Part 11: self-approval redirect combined with a duplicate-
    // approver skip, at the SAME level, alongside a genuinely distinct split owner who must never be
    // affected by either.
    // ---------------------------------------------------------------------

    @Test
    void selfApprovalAndDuplicateSkip_combineCorrectly_withoutAffectingAGenuinelyDistinctSplitOwner() {
        String redirectManagerId = "5100070";
        String splitBOwnerId = "5100062";
        // The submitter owns BOTH the report's header Cost Center AND split A's Cost Center - two
        // separate ApprovalAssignment rows (header, from the entries loop; split-owner, from
        // createSplitOwnerAssignments) that both resolve to the submitter and must both be redirected.
        CostCenter headerCcOwnedBySubmitter = costCenterOwnedBy(submitterId, false);
        CostCenter ccA = costCenterOwnedBy(submitterId, false);
        CostCenter ccB = costCenterOwnedBy(splitBOwnerId, false);
        ExpenseReport report = draftReportWithSplits("placeholder"); // overwritten below with the real header CC
        report.setCostCenter(headerCcOwnedBySubmitter);
        ExpenseLineItem lineItem = report.getExpenseLineItems().get(0);
        ExpenseSplit splitA = split(lineItem, ccA, "600", 1);
        ExpenseSplit splitB = split(lineItem, ccB, "400", 2);
        lineItem.setExpenseSplits(List.of(splitA, splitB));
        ApprovalFlow flow = costCenterOwnerOnlyFlow(submitterId); // header entry resolves to the submitter too
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(flow);
        stubBudget(ccA, budgetWith(ccA, "20000"));
        stubBudget(ccB, budgetWith(ccB, "20000"));
        when(employeeCacheRepository.findByEmployeeId(submitterId))
                .thenReturn(Optional.of(EmployeeCache.builder().employeeId(submitterId).managerEmployeeId(redirectManagerId).build()));

        service.submit(reportId);

        // Both of the submitter's own assignments (header + split A) were redirected to their
        // manager; the duplicate-approver pass then correctly skipped the SECOND occurrence of that
        // same manager at this level - split A's assignment - waiving split A entirely, per the
        // already-established "a skipped split-owner's splits are waived" design.
        assertThat(savedAssignments.stream().filter(a -> a.getApproverId().equals(submitterId))).isEmpty(); // no assignment is left resolved to the submitter themselves
        long activeForManager = savedAssignments.stream()
                .filter(a -> a.getApproverId().equals(redirectManagerId) && a.getStatus() == AssignmentStatus.ACTIVE).count();
        long skippedForManager = savedAssignments.stream()
                .filter(a -> a.getApproverId().equals(redirectManagerId) && a.getStatus() == AssignmentStatus.SKIPPED).count();
        assertThat(activeForManager).isEqualTo(1);
        assertThat(skippedForManager).isEqualTo(1);

        // Split B's owner is a genuinely distinct person, never touched by either correctness pass.
        assertThat(assignmentFor(splitBOwnerId).getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(assignmentFor(splitBOwnerId).getSplitReviews()).hasSize(1);

        // Level completes via the manager's one remaining responsibility + split B's genuine owner -
        // nobody ever needs to (or can) act on the waived split A.
        service.reviewLineItem(reportId, lineItemId, redirectManagerId, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        service.reviewSplit(reportId, splitB.getSplitId(), splitBOwnerId, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));

        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.APPROVED);
    }

    // ---------------------------------------------------------------------
    // 7. Workflow progression bug: Level 2 (Cost Center Owner) must complete and Level 3 must
    // activate once every split review is APPROVED, even when the level's own redundant normal-track
    // entry was auto-skipped as a cross-level duplicate of an earlier level's approver.
    // ---------------------------------------------------------------------

    /**
     * Regression test for the exact real-database bug report: Level 1 Reporting Manager (here, a
     * NAMED_USER stand-in) is the SAME person as Level 2's header Cost Center Owner - so Level 2
     * resolves a redundant normal-track entry (no split reviews - the line item is fully split, there
     * is no unsplit remainder for it to review) alongside the two genuine split-owner assignments
     * (Engineering, HR). The redundant entry is a genuine cross-level duplicate and gets auto-skipped;
     * that must not prevent Level 2 from completing once both split reviews are APPROVED, and Level 3
     * must then activate automatically with its own approver visible in {@code getMyQueue}.
     */
    @Test
    void bothSplitOwnersApprove_completesLevel2_andActivatesLevel3_evenWhenTheRedundantHeaderEntryWasSkipped() {
        String engineeringOwner = "5100023"; // also Level 1's Reporting-Manager stand-in
        String hrOwner = "5100022";
        String financeApprover = "5100009";

        ExpenseReport report = draftReportWithSplits(engineeringOwner); // header Cost Center owned by engineeringOwner
        CostCenter engineeringCc = report.getCostCenter(); // the split reuses the SAME Cost Center as the header - reproducing the real bug's exact shape
        CostCenter hrCc = costCenterOwnedBy(hrOwner, false);
        ExpenseLineItem lineItem = report.getExpenseLineItems().get(0);
        ExpenseSplit engineeringSplit = split(lineItem, engineeringCc, "600", 1);
        ExpenseSplit hrSplit = split(lineItem, hrCc, "400", 2);
        lineItem.setExpenseSplits(List.of(engineeringSplit, hrSplit));

        ApprovalFlow flow = reportingManagerThenCostCenterOwnerThenFinanceFlow(engineeringOwner, financeApprover);
        when(approvalFlowResolutionService.resolveMatchingFlow(report)).thenReturn(flow);
        stubBudget(engineeringCc, budgetWith(engineeringCc, "20000"));
        stubBudget(hrCc, budgetWith(hrCc, "20000"));

        service.submit(reportId);
        assertThat(currentActiveLevelOrder()).isEqualTo(1);

        // Level 1 (Reporting Manager) approves - Level 2 activates. Its redundant header entry (same
        // person) is auto-skipped as a cross-level duplicate; the genuine split-owner assignments survive.
        service.reviewLineItem(reportId, lineItemId, engineeringOwner, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        assertThat(currentActiveLevelOrder()).isEqualTo(2);
        assertThat(savedAssignments.stream()
                .filter(a -> a.getApproverId().equals(engineeringOwner) && a.getSplitReviews().isEmpty())
                .findFirst().orElseThrow().getStatus()).isEqualTo(AssignmentStatus.SKIPPED);

        // Only the Engineering split approved so far - Level 2 must NOT complete yet.
        service.reviewSplit(reportId, engineeringSplit.getSplitId(), engineeringOwner, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        assertThat(currentActiveLevelOrder()).isEqualTo(2);

        // Both split reviews are now APPROVED - Level 2 must complete and Level 3 must activate.
        service.reviewSplit(reportId, hrSplit.getSplitId(), hrOwner, new LineItemReviewRequest(LineItemReviewStatus.APPROVED, null));
        assertThat(currentActiveLevelOrder()).isEqualTo(3);

        var financeQueue = service.getMyQueue(financeApprover, PageRequest.of(0, 20));
        assertThat(financeQueue.content()).hasSize(1);
        assertThat(financeQueue.content().get(0).reportId()).isEqualTo(reportId);
    }
}
