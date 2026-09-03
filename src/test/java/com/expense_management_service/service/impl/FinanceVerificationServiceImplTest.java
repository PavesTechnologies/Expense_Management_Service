package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.entity.ApprovalAssignment;
import com.expense_management_service.entity.ApprovalLevelInstance;
import com.expense_management_service.entity.ExpenseCategory;
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
import com.expense_management_service.repository.FinanceVerificationReviewRepository;
import com.expense_management_service.service.ApprovalEventPublisher;
import com.expense_management_service.service.ApprovalWorkflowService;
import com.expense_management_service.service.DelegationService;
import com.expense_management_service.service.FinanceEligibilityResult;
import com.expense_management_service.service.FinanceVerificationEligibilityChecker;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class FinanceVerificationServiceImplTest {

    @Mock private ExpenseReportRepository expenseReportRepository;
    @Mock private ApprovalLevelInstanceRepository approvalLevelInstanceRepository;
    @Mock private ApprovalAssignmentRepository approvalAssignmentRepository;
    @Mock private FinanceVerificationReviewRepository financeVerificationReviewRepository;
    @Mock private ExpenseLineItemRepository expenseLineItemRepository;
    @Mock private FinanceVerificationEligibilityChecker financeVerificationEligibilityChecker;
    @Mock private ApprovalEventPublisher approvalEventPublisher;
    @Mock private ApprovalWorkflowService approvalWorkflowService;
    @Mock private com.expense_management_service.repository.PolicyViolationRepository policyViolationRepository;
    @Mock private com.expense_management_service.repository.VerificationQueryRepository verificationQueryRepository;

    private FinanceVerificationServiceImpl service;

    private final UUID reportId = UUID.randomUUID();
    private final UUID instanceId = UUID.randomUUID();
    private final UUID lineItemId = UUID.randomUUID();
    private final String approverId = "5100050";

    @BeforeEach
    void setUp() {
        var factory = new ExpenseReportResponseFactory(new com.expense_management_service.mapper.ExpenseReportMapper(), policyViolationRepository);
        service = new FinanceVerificationServiceImpl(expenseReportRepository, approvalLevelInstanceRepository,
                approvalAssignmentRepository, financeVerificationReviewRepository, expenseLineItemRepository,
                financeVerificationEligibilityChecker, approvalEventPublisher, approvalWorkflowService, factory,
                verificationQueryRepository);

        when(policyViolationRepository.findByLineItem_Report_ReportId(any())).thenReturn(List.of());
    }

    private ExpenseReport pendingFinanceReport() {
        return ExpenseReport.builder().reportId(reportId).employeeId("5100001").reportStatus(ReportStatus.PENDING_FINANCE_VERIFICATION).build();
    }

    private ApprovalLevelInstance financeInstance() {
        return ApprovalLevelInstance.builder().instanceId(instanceId).levelType(LevelType.FINANCE_VERIFICATION).status(LevelInstanceStatus.ACTIVE).build();
    }

    private ExpenseLineItem lineItem() {
        GlAccount glAccount = GlAccount.builder().glAccountId(UUID.randomUUID()).glAccountCode("6100").status("ACTIVE").build();
        ExpenseCategory category = ExpenseCategory.builder().categoryName("Travel").receiptRequired(false).glAccount(glAccount).build();
        return ExpenseLineItem.builder().lineItemId(lineItemId).report(pendingFinanceReport()).category(category).receipts(List.of()).build();
    }

    private void stubHappyPathUpTo(FinanceVerificationReview review) {
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(pendingFinanceReport()));
        when(approvalLevelInstanceRepository.findMaxSubmissionCycle(reportId)).thenReturn(1);
        when(approvalLevelInstanceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, 1, LevelInstanceStatus.ACTIVE))
                .thenReturn(Optional.of(financeInstance()));
        ApprovalAssignment assignment = ApprovalAssignment.builder().assignmentId(UUID.randomUUID()).approverId(approverId).status(AssignmentStatus.ACTIVE).build();
        when(approvalAssignmentRepository.findByLevelInstance_InstanceId(instanceId)).thenReturn(List.of(assignment));
        when(expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId)).thenReturn(Optional.of(lineItem()));
        when(financeVerificationReviewRepository.findByLineItem_LineItemIdAndLevelInstance_InstanceId(lineItemId, instanceId))
                .thenReturn(Optional.of(review));
        when(financeVerificationReviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void verifyLineItem_throws_whenReportNotPendingFinanceVerification() {
        ExpenseReport report = ExpenseReport.builder().reportId(reportId).reportStatus(ReportStatus.PENDING_APPROVAL).build();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.verifyLineItem(reportId, lineItemId, approverId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no Finance Verification level currently active");
    }

    @Test
    void verifyLineItem_succeeds_forAnyFinanceExecutive_notJustTheResolvedCostCenterApprover() {
        // §8: Finance Verification access is role+status based (checked by @PreAuthorize at the
        // controller), not tied to being the specific FinanceTeamApprover resolved for this
        // report's cost center - so a different Finance Executive can still act on it.
        FinanceVerificationReview review = FinanceVerificationReview.builder().reviewId(UUID.randomUUID()).status(FinanceVerificationStatus.PENDING).build();
        stubHappyPathUpTo(review);
        when(financeVerificationEligibilityChecker.check(any())).thenReturn(FinanceEligibilityResult.ok());

        ExpenseReportResponse response = service.verifyLineItem(reportId, lineItemId, "someoneElse");

        assertThat(response).isNotNull();
        assertThat(review.getStatus()).isEqualTo(FinanceVerificationStatus.VERIFIED);
        assertThat(review.getActedBy()).isEqualTo("someoneElse");
    }

    @Test
    void verifyLineItem_throws_whenAlreadyVerified() {
        FinanceVerificationReview review = FinanceVerificationReview.builder().reviewId(UUID.randomUUID()).status(FinanceVerificationStatus.VERIFIED).build();
        stubHappyPathUpTo(review);

        assertThatThrownBy(() -> service.verifyLineItem(reportId, lineItemId, approverId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already been reviewed");
    }

    @Test
    void verifyLineItem_throws_withEligibilityReason_whenIneligible() {
        FinanceVerificationReview review = FinanceVerificationReview.builder().reviewId(UUID.randomUUID()).status(FinanceVerificationStatus.PENDING).build();
        stubHappyPathUpTo(review);
        when(financeVerificationEligibilityChecker.check(any())).thenReturn(FinanceEligibilityResult.blocked("Receipt is missing."));

        assertThatThrownBy(() -> service.verifyLineItem(reportId, lineItemId, approverId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Receipt is missing.");

        verify(financeVerificationReviewRepository, never()).save(any());
    }

    @Test
    void verifyLineItem_marksVerified_snapshotsGlAccount_andAdvancesWorkflow_whenEligible() {
        FinanceVerificationReview review = FinanceVerificationReview.builder().reviewId(UUID.randomUUID()).status(FinanceVerificationStatus.PENDING).build();
        stubHappyPathUpTo(review);
        when(financeVerificationEligibilityChecker.check(any())).thenReturn(FinanceEligibilityResult.ok());

        ExpenseReportResponse response = service.verifyLineItem(reportId, lineItemId, approverId);

        assertThat(response).isNotNull();
        assertThat(review.getStatus()).isEqualTo(FinanceVerificationStatus.VERIFIED);
        assertThat(review.getGlAccountCodeSnapshot()).isEqualTo("6100");
        assertThat(review.getPolicyExceptionResolvedFlag()).isTrue();
        assertThat(review.getReceiptValidatedFlag()).isTrue();
        assertThat(review.getActedBy()).isEqualTo(approverId);
        verify(approvalEventPublisher).publish(eq("LINE_ITEM_VERIFIED"), eq(reportId), any());
        verify(approvalWorkflowService).advanceAfterLevelReviewed(reportId, instanceId, approverId);
    }

    @Test
    void queryLineItem_throws_whenReasonIsBlank() {
        assertThatThrownBy(() -> service.queryLineItem(reportId, lineItemId, approverId, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason is required");
    }

    @Test
    void queryLineItem_marksQueried_raisesVerificationQuery_andSetsReportAwaitingCorrection_withoutAdvancingWorkflow() {
        FinanceVerificationReview review = FinanceVerificationReview.builder().reviewId(UUID.randomUUID()).status(FinanceVerificationStatus.PENDING).build();
        stubHappyPathUpTo(review);
        ExpenseReport report = pendingFinanceReport();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        service.queryLineItem(reportId, lineItemId, approverId, "Missing itemized bill");

        assertThat(review.getStatus()).isEqualTo(FinanceVerificationStatus.QUERIED);
        assertThat(review.getComment()).isEqualTo("Missing itemized bill");
        verify(verificationQueryRepository).save(any());
        verify(approvalEventPublisher).publish(eq("VERIFICATION_QUERY_RAISED"), eq(reportId), any());
        verify(approvalWorkflowService, never()).advanceAfterLevelReviewed(any(), any(), any());
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.AWAITING_CORRECTION);
    }

    @Test
    void queryLineItem_throws_whenAlreadyQueried() {
        FinanceVerificationReview review = FinanceVerificationReview.builder().reviewId(UUID.randomUUID()).status(FinanceVerificationStatus.QUERIED).build();
        stubHappyPathUpTo(review);

        assertThatThrownBy(() -> service.queryLineItem(reportId, lineItemId, approverId, "Second query"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already been reviewed");
    }

    @Test
    void getFinanceQueue_returnsEveryReportPendingFinanceVerification_regardlessOfCaller() {
        // §8: role+status based - not filtered by any per-report assignment, so the acting
        // employee id passed in doesn't change which reports come back.
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        ExpenseReport report = pendingFinanceReport();
        when(expenseReportRepository.findByReportStatus(ReportStatus.PENDING_FINANCE_VERIFICATION, pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(report), pageable, 1));
        when(approvalLevelInstanceRepository.findMaxSubmissionCycle(reportId)).thenReturn(1);
        ApprovalLevelInstance instance = financeInstance();
        when(approvalLevelInstanceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, 1, LevelInstanceStatus.ACTIVE))
                .thenReturn(Optional.of(instance));
        when(financeVerificationReviewRepository.findByLevelInstance_InstanceIdAndStatus(instanceId, FinanceVerificationStatus.PENDING))
                .thenReturn(List.of());

        var page = service.getFinanceQueue("anyFinanceExecutive", pageable);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).reportId()).isEqualTo(reportId);
        assertThat(page.content().get(0).levelOrder()).isEqualTo(instance.getLevelOrder());
    }

    @Test
    void getFinanceReviews_returnsReviews_onlyForFinanceVerificationInstances() {
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(pendingFinanceReport()));
        when(approvalLevelInstanceRepository.findMaxSubmissionCycle(reportId)).thenReturn(1);
        ApprovalLevelInstance managerInstance = ApprovalLevelInstance.builder().instanceId(UUID.randomUUID())
                .levelOrder(1).levelType(LevelType.APPROVAL).build();
        ApprovalLevelInstance financeInst = financeInstance();
        financeInst.setLevelOrder(2);
        when(approvalLevelInstanceRepository.findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(reportId, 1))
                .thenReturn(List.of(managerInstance, financeInst));
        FinanceVerificationReview review = FinanceVerificationReview.builder().reviewId(UUID.randomUUID())
                .lineItem(lineItem()).levelInstance(financeInst).status(FinanceVerificationStatus.VERIFIED).build();
        when(financeVerificationReviewRepository.findByLevelInstance_InstanceId(financeInst.getInstanceId())).thenReturn(List.of(review));

        var result = service.getFinanceReviews(reportId, "5100001");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo(FinanceVerificationStatus.VERIFIED);
        assertThat(result.get(0).levelOrder()).isEqualTo(2);
    }
}
