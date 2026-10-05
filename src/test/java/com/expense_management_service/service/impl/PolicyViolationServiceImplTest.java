package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ApproverExceptionRequest;
import com.expense_management_service.dto.request.PolicyJustificationRequest;
import com.expense_management_service.dto.response.PolicyWarningResponse;
import com.expense_management_service.entity.ApprovalAssignment;
import com.expense_management_service.entity.ApprovalLevelInstance;
import com.expense_management_service.entity.ApprovalSplitReview;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.PolicyViolation;
import com.expense_management_service.enums.AssignmentStatus;
import com.expense_management_service.enums.LevelInstanceStatus;
import com.expense_management_service.enums.LevelType;
import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.enums.PolicySeverity;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.mapper.PolicyViolationMapper;
import com.expense_management_service.repository.ApprovalAssignmentRepository;
import com.expense_management_service.repository.ApprovalLevelInstanceRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.PolicyViolationRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.AuditLogService;
import com.expense_management_service.service.DelegationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyViolationServiceImplTest {

    @Mock
    private ExpenseReportRepository expenseReportRepository;
    @Mock
    private ExpenseLineItemRepository expenseLineItemRepository;
    @Mock
    private PolicyViolationRepository policyViolationRepository;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private ApprovalLevelInstanceRepository approvalLevelInstanceRepository;
    @Mock
    private ApprovalAssignmentRepository approvalAssignmentRepository;
    @Mock
    private DelegationService delegationService;
    @Mock
    private AuditLogService auditLogService;

    private PolicyViolationServiceImpl policyViolationService;

    private final String employeeId = "5100014";
    private final String approverId = "5100777";
    private UUID reportId;
    private UUID lineItemId;
    private ExpenseReport draftReport;
    private ExpenseLineItem lineItem;

    @BeforeEach
    void setUp() {
        policyViolationService = new PolicyViolationServiceImpl(
                expenseReportRepository, expenseLineItemRepository, policyViolationRepository,
                new PolicyViolationMapper(), currentUserService,
                approvalLevelInstanceRepository, approvalAssignmentRepository, delegationService, auditLogService);
        ReflectionTestUtils.setField(policyViolationService, "justificationMinLength", 20);

        reportId = UUID.randomUUID();
        lineItemId = UUID.randomUUID();
        draftReport = ExpenseReport.builder().reportId(reportId).employeeId(employeeId).reportStatus(ReportStatus.DRAFT).build();
        lineItem = ExpenseLineItem.builder().lineItemId(lineItemId).report(draftReport).build();
    }

    private ApprovalLevelInstance activeApprovalInstance() {
        return ApprovalLevelInstance.builder().instanceId(UUID.randomUUID()).levelType(LevelType.APPROVAL)
                .status(LevelInstanceStatus.ACTIVE).build();
    }

    private ApprovalAssignment normalTrackAssignment(String assignedApproverId) {
        return ApprovalAssignment.builder().assignmentId(UUID.randomUUID())
                .approverId(assignedApproverId).status(AssignmentStatus.ACTIVE)
                .splitReviews(new java.util.ArrayList<>()).build();
    }

    private ApprovalAssignment splitOwnerOnlyAssignment(String assignedApproverId) {
        return ApprovalAssignment.builder().assignmentId(UUID.randomUUID())
                .approverId(assignedApproverId).status(AssignmentStatus.ACTIVE)
                .splitReviews(new java.util.ArrayList<>(List.of(ApprovalSplitReview.builder().build())))
                .build();
    }

    private CurrentUser employeeCaller() {
        return new CurrentUser(UUID.randomUUID(), null, employeeId, "jordan@example.com", "Jordan", List.of("EMPLOYEE"), List.of());
    }

    private PolicyViolation violation(UUID violationId) {
        return PolicyViolation.builder().violationId(violationId).lineItem(lineItem)
                .ruleType(PolicyRuleType.MISSING_DESCRIPTION).severity(PolicySeverity.WARN)
                .message("This expense is missing a description").build();
    }

    private PolicyViolation crossEmployeeViolation(UUID violationId) {
        return PolicyViolation.builder().violationId(violationId).lineItem(lineItem)
                .ruleType(PolicyRuleType.CROSS_EMPLOYEE_DUPLICATE_EXPENSE).severity(PolicySeverity.WARN)
                .message("Possible duplicate/shared bill: employee 5100099 submitted a matching expense "
                        + "(same vendor, date, amount, and currency) on line item " + UUID.randomUUID()
                        + " - recommend Finance review before approving.")
                .build();
    }

    @Test
    void getForLineItem_returnsWarnings_whenOwner() {
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));
        when(expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId)).thenReturn(Optional.of(lineItem));
        when(policyViolationRepository.findByLineItem_LineItemId(lineItemId)).thenReturn(List.of(violation(UUID.randomUUID())));

        List<PolicyWarningResponse> responses = policyViolationService.getForLineItem(reportId, lineItemId);

        assertThat(responses).hasSize(1);
    }

    @Test
    void getForLineItem_throwsAccessDenied_whenNotOwnerOrPrivileged() {
        draftReport.setEmployeeId("someone-else");
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));

        assertThatThrownBy(() -> policyViolationService.getForLineItem(reportId, lineItemId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getForLineItem_returnsWarnings_forApExecutive_onSomeoneElsesReport() {
        draftReport.setEmployeeId("someone-else");
        when(currentUserService.getCurrentUser()).thenReturn(
                new CurrentUser(UUID.randomUUID(), null, "ap-user", "ap@example.com", "AP", List.of("AP_EXECUTIVE"), List.of()));
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));
        when(expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId)).thenReturn(Optional.of(lineItem));
        when(policyViolationRepository.findByLineItem_LineItemId(lineItemId)).thenReturn(List.of());

        List<PolicyWarningResponse> responses = policyViolationService.getForLineItem(reportId, lineItemId);

        assertThat(responses).isEmpty();
    }

    @Test
    void getForLineItem_returnsWarnings_forFinanceExecutive_onSomeoneElsesReport() {
        draftReport.setEmployeeId("someone-else");
        when(currentUserService.getCurrentUser()).thenReturn(
                new CurrentUser(UUID.randomUUID(), null, "finance-user", "finance@example.com", "Finance", List.of("FINANCE_EXECUTIVE"), List.of()));
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));
        when(expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId)).thenReturn(Optional.of(lineItem));
        when(policyViolationRepository.findByLineItem_LineItemId(lineItemId)).thenReturn(List.of());

        List<PolicyWarningResponse> responses = policyViolationService.getForLineItem(reportId, lineItemId);

        assertThat(responses).isEmpty();
    }

    @Test
    void getForLineItem_redactsCrossEmployeeMessage_forOwner() {
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));
        when(expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId)).thenReturn(Optional.of(lineItem));
        when(policyViolationRepository.findByLineItem_LineItemId(lineItemId)).thenReturn(List.of(crossEmployeeViolation(UUID.randomUUID())));

        List<PolicyWarningResponse> responses = policyViolationService.getForLineItem(reportId, lineItemId);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).message()).doesNotContain("5100099");
        assertThat(responses.get(0).message()).isEqualTo(
                "Another employee submitted a matching expense (same vendor, date, amount, and currency) — under Finance review");
    }

    @Test
    void getForLineItem_showsFullCrossEmployeeMessage_forFinance() {
        draftReport.setEmployeeId("someone-else");
        when(currentUserService.getCurrentUser()).thenReturn(
                new CurrentUser(UUID.randomUUID(), null, "finance-user", "finance@example.com", "Finance", List.of("FINANCE"), List.of()));
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));
        when(expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId)).thenReturn(Optional.of(lineItem));
        when(policyViolationRepository.findByLineItem_LineItemId(lineItemId)).thenReturn(List.of(crossEmployeeViolation(UUID.randomUUID())));

        List<PolicyWarningResponse> responses = policyViolationService.getForLineItem(reportId, lineItemId);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).message()).contains("5100099");
    }

    @Test
    void justify_savesJustification_whenValid() {
        UUID violationId = UUID.randomUUID();
        PolicyViolation existing = violation(violationId);
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));
        when(expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId)).thenReturn(Optional.of(lineItem));
        when(policyViolationRepository.findByViolationIdAndLineItem_LineItemId(violationId, lineItemId)).thenReturn(Optional.of(existing));
        when(policyViolationRepository.save(any(PolicyViolation.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyWarningResponse response = policyViolationService.justify(reportId, lineItemId, violationId,
                new PolicyJustificationRequest("Client specifically requested no itemised memo for this trip"));

        assertThat(response.justification()).isEqualTo("Client specifically requested no itemised memo for this trip");
        assertThat(response.justifiedAt()).isNotNull();
    }

    @Test
    void justify_throwsIllegalArgument_whenTooShort() {
        UUID violationId = UUID.randomUUID();
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));
        when(expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId)).thenReturn(Optional.of(lineItem));

        assertThatThrownBy(() -> policyViolationService.justify(reportId, lineItemId, violationId,
                new PolicyJustificationRequest("too short")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void justify_throwsAccessDenied_whenNotOwner() {
        draftReport.setEmployeeId("someone-else");
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));

        assertThatThrownBy(() -> policyViolationService.justify(reportId, lineItemId, UUID.randomUUID(),
                new PolicyJustificationRequest("Client specifically requested no itemised memo")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void justify_throwsBusinessRuleViolation_whenReportNotEditable() {
        draftReport.setReportStatus(ReportStatus.PENDING_APPROVAL);
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));

        assertThatThrownBy(() -> policyViolationService.justify(reportId, lineItemId, UUID.randomUUID(),
                new PolicyJustificationRequest("Client specifically requested no itemised memo")))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void justify_throwsResourceNotFound_whenViolationNotOnLineItem() {
        UUID violationId = UUID.randomUUID();
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));
        when(expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId)).thenReturn(Optional.of(lineItem));
        when(policyViolationRepository.findByViolationIdAndLineItem_LineItemId(violationId, lineItemId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> policyViolationService.justify(reportId, lineItemId, violationId,
                new PolicyJustificationRequest("Client specifically requested no itemised memo")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- approveException: a SEPARATE, approver-side authorization from justify() above ----

    @Test
    void approveException_authorizesException_whenActiveApprover() {
        UUID violationId = UUID.randomUUID();
        PolicyViolation existing = violation(violationId);
        ApprovalLevelInstance activeInstance = activeApprovalInstance();
        when(expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId)).thenReturn(Optional.of(lineItem));
        when(policyViolationRepository.findByViolationIdAndLineItem_LineItemId(violationId, lineItemId)).thenReturn(Optional.of(existing));
        when(approvalLevelInstanceRepository.findMaxSubmissionCycle(reportId)).thenReturn(1);
        when(approvalLevelInstanceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, 1, LevelInstanceStatus.ACTIVE))
                .thenReturn(Optional.of(activeInstance));
        when(approvalAssignmentRepository.findByLevelInstance_InstanceId(activeInstance.getInstanceId()))
                .thenReturn(List.of(normalTrackAssignment(approverId)));
        when(delegationService.canAct(approverId, approverId)).thenReturn(true);
        when(policyViolationRepository.save(any(PolicyViolation.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyWarningResponse response = policyViolationService.approveException(reportId, lineItemId, violationId, approverId,
                new ApproverExceptionRequest("No cheaper hotel was available in the client's area for this trip"));

        assertThat(response.approverJustification()).isEqualTo("No cheaper hotel was available in the client's area for this trip");
        assertThat(response.approverJustifiedBy()).isEqualTo(approverId);
        assertThat(response.approverJustifiedAt()).isNotNull();
    }

    @Test
    void approveException_deniedForNonApproverOrDelegate() {
        UUID violationId = UUID.randomUUID();
        ApprovalLevelInstance activeInstance = activeApprovalInstance();
        lenient().when(expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId)).thenReturn(Optional.of(lineItem));
        lenient().when(policyViolationRepository.findByViolationIdAndLineItem_LineItemId(violationId, lineItemId)).thenReturn(Optional.of(violation(violationId)));
        when(approvalLevelInstanceRepository.findMaxSubmissionCycle(reportId)).thenReturn(1);
        when(approvalLevelInstanceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, 1, LevelInstanceStatus.ACTIVE))
                .thenReturn(Optional.of(activeInstance));
        when(approvalAssignmentRepository.findByLevelInstance_InstanceId(activeInstance.getInstanceId()))
                .thenReturn(List.of(normalTrackAssignment(approverId)));
        when(delegationService.canAct("someone-unrelated", approverId)).thenReturn(false);

        assertThatThrownBy(() -> policyViolationService.approveException(reportId, lineItemId, violationId, "someone-unrelated",
                new ApproverExceptionRequest("Trying to authorize an exception I have no standing to authorize")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void approveException_deniedForSplitOwnerOnlyAssignment() {
        UUID violationId = UUID.randomUUID();
        ApprovalLevelInstance activeInstance = activeApprovalInstance();
        lenient().when(expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId)).thenReturn(Optional.of(lineItem));
        lenient().when(policyViolationRepository.findByViolationIdAndLineItem_LineItemId(violationId, lineItemId)).thenReturn(Optional.of(violation(violationId)));
        when(approvalLevelInstanceRepository.findMaxSubmissionCycle(reportId)).thenReturn(1);
        when(approvalLevelInstanceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, 1, LevelInstanceStatus.ACTIVE))
                .thenReturn(Optional.of(activeInstance));
        // Only a split-owner assignment resolves for this approver - never a valid stand-in for a
        // shared, line-item-level action like an exception authorization.
        when(approvalAssignmentRepository.findByLevelInstance_InstanceId(activeInstance.getInstanceId()))
                .thenReturn(List.of(splitOwnerOnlyAssignment(approverId)));

        assertThatThrownBy(() -> policyViolationService.approveException(reportId, lineItemId, violationId, approverId,
                new ApproverExceptionRequest("Cost Center Owner trying to act outside their split-owner role")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void approveException_rejectsSecondAttempt_whenAlreadyAuthorized() {
        UUID violationId = UUID.randomUUID();
        PolicyViolation alreadyAuthorized = violation(violationId);
        alreadyAuthorized.setApproverJustification("Already approved once");
        alreadyAuthorized.setApproverJustifiedBy("5100888");
        alreadyAuthorized.setApproverJustifiedAt(java.time.LocalDateTime.now().minusDays(1));
        when(expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId)).thenReturn(Optional.of(lineItem));
        when(policyViolationRepository.findByViolationIdAndLineItem_LineItemId(violationId, lineItemId)).thenReturn(Optional.of(alreadyAuthorized));

        assertThatThrownBy(() -> policyViolationService.approveException(reportId, lineItemId, violationId, approverId,
                new ApproverExceptionRequest("Trying to authorize the same violation a second time")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5100888");
    }

    @Test
    void approveException_throwsIllegalArgument_whenNoActiveApprovalLevel() {
        UUID violationId = UUID.randomUUID();
        when(expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId)).thenReturn(Optional.of(lineItem));
        when(policyViolationRepository.findByViolationIdAndLineItem_LineItemId(violationId, lineItemId)).thenReturn(Optional.of(violation(violationId)));
        when(approvalLevelInstanceRepository.findMaxSubmissionCycle(reportId)).thenReturn(1);
        when(approvalLevelInstanceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, 1, LevelInstanceStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> policyViolationService.approveException(reportId, lineItemId, violationId, approverId,
                new ApproverExceptionRequest("No level is currently active to authorize against")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approveException_throwsIllegalArgument_whenActiveLevelIsFinanceVerification() {
        UUID violationId = UUID.randomUUID();
        ApprovalLevelInstance financeInstance = ApprovalLevelInstance.builder().instanceId(UUID.randomUUID())
                .levelType(LevelType.FINANCE_VERIFICATION).status(LevelInstanceStatus.ACTIVE).build();
        when(expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId)).thenReturn(Optional.of(lineItem));
        when(policyViolationRepository.findByViolationIdAndLineItem_LineItemId(violationId, lineItemId)).thenReturn(Optional.of(violation(violationId)));
        when(approvalLevelInstanceRepository.findMaxSubmissionCycle(reportId)).thenReturn(1);
        when(approvalLevelInstanceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, 1, LevelInstanceStatus.ACTIVE))
                .thenReturn(Optional.of(financeInstance));

        assertThatThrownBy(() -> policyViolationService.approveException(reportId, lineItemId, violationId, approverId,
                new ApproverExceptionRequest("Trying to authorize at a Finance Verification level")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approveException_throwsIllegalArgument_whenJustificationTooShort() {
        assertThatThrownBy(() -> policyViolationService.approveException(reportId, lineItemId, UUID.randomUUID(), approverId,
                new ApproverExceptionRequest("too short")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
