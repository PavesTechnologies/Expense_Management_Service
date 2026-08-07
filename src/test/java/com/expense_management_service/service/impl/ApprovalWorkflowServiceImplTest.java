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
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.ApproverSourceType;
import com.expense_management_service.enums.AssignmentStatus;
import com.expense_management_service.enums.LevelInstanceStatus;
import com.expense_management_service.enums.LevelQuorum;
import com.expense_management_service.enums.LineItemReviewStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.mapper.ExpenseReportMapper;
import com.expense_management_service.repository.ApprovalAssignmentRepository;
import com.expense_management_service.repository.ApprovalLevelInstanceRepository;
import com.expense_management_service.repository.ApprovalLineItemReviewRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    @Mock private PolicyViolationRepository policyViolationRepository;
    @Mock private ApprovalFlowResolutionService approvalFlowResolutionService;
    @Mock private ApproverSourceResolver approverSourceResolver;
    @Mock private ChainCorrectnessService chainCorrectnessService;
    @Mock private DelegationService delegationService;
    @Mock private PolicyEvaluationGateway policyEvaluationGateway;
    @Mock private ApprovalEventPublisher approvalEventPublisher;
    @Mock private SlaPolicyService slaPolicyService;

    private ApprovalWorkflowServiceImpl service;

    private final UUID reportId = UUID.randomUUID();
    private final UUID flowId = UUID.randomUUID();
    private final UUID lineItemId = UUID.randomUUID();
    private final String submitterId = "5100001";
    private final String approverId = "5100002";

    private final List<ApprovalLevelInstance> savedInstances = new ArrayList<>();
    private final List<ApprovalAssignment> savedAssignments = new ArrayList<>();
    private final List<ApprovalLineItemReview> savedReviews = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ApprovalWorkflowServiceImpl(expenseReportRepository, approvalLevelInstanceRepository,
                approvalAssignmentRepository, approvalLineItemReviewRepository, policyViolationRepository,
                approvalFlowResolutionService, approverSourceResolver, chainCorrectnessService, delegationService,
                policyEvaluationGateway, approvalEventPublisher, new ExpenseReportMapper(), slaPolicyService);

        when(policyEvaluationGateway.evaluate(any())).thenReturn(new PolicyDecision(true, List.of()));
        when(policyViolationRepository.findByLineItem_Report_ReportId(any())).thenReturn(List.of());
        when(slaPolicyService.resolveSlaBusinessDays()).thenReturn(3);
        when(delegationService.canAct(any(), any())).thenAnswer(inv -> inv.getArgument(0).equals(inv.getArgument(1)));

        when(approvalLevelInstanceRepository.save(any(ApprovalLevelInstance.class))).thenAnswer(inv -> {
            ApprovalLevelInstance i = inv.getArgument(0);
            if (i.getInstanceId() == null) {
                i.setInstanceId(UUID.randomUUID());
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
}
