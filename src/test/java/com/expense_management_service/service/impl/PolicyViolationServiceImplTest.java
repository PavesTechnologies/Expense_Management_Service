package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.PolicyJustificationRequest;
import com.expense_management_service.dto.response.PolicyWarningResponse;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.PolicyViolation;
import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.enums.PolicySeverity;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.mapper.PolicyViolationMapper;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.PolicyViolationRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
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

    private PolicyViolationServiceImpl policyViolationService;

    private final String employeeId = "5100014";
    private UUID reportId;
    private UUID lineItemId;
    private ExpenseReport draftReport;
    private ExpenseLineItem lineItem;

    @BeforeEach
    void setUp() {
        policyViolationService = new PolicyViolationServiceImpl(
                expenseReportRepository, expenseLineItemRepository, policyViolationRepository,
                new PolicyViolationMapper(), currentUserService);
        ReflectionTestUtils.setField(policyViolationService, "justificationMinLength", 20);

        reportId = UUID.randomUUID();
        lineItemId = UUID.randomUUID();
        draftReport = ExpenseReport.builder().reportId(reportId).employeeId(employeeId).reportStatus(ReportStatus.DRAFT).build();
        lineItem = ExpenseLineItem.builder().lineItemId(lineItemId).report(draftReport).build();
    }

    private CurrentUser employeeCaller() {
        return new CurrentUser(UUID.randomUUID(), employeeId, "jordan@example.com", "Jordan", List.of("EMPLOYEE"), List.of());
    }

    private PolicyViolation violation(UUID violationId) {
        return PolicyViolation.builder().violationId(violationId).lineItem(lineItem)
                .ruleType(PolicyRuleType.MISSING_DESCRIPTION).severity(PolicySeverity.WARN)
                .message("This expense is missing a description").build();
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
                new CurrentUser(UUID.randomUUID(), "ap-user", "ap@example.com", "AP", List.of("AP_EXECUTIVE"), List.of()));
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
                new CurrentUser(UUID.randomUUID(), "finance-user", "finance@example.com", "Finance", List.of("FINANCE_EXECUTIVE"), List.of()));
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));
        when(expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId)).thenReturn(Optional.of(lineItem));
        when(policyViolationRepository.findByLineItem_LineItemId(lineItemId)).thenReturn(List.of());

        List<PolicyWarningResponse> responses = policyViolationService.getForLineItem(reportId, lineItemId);

        assertThat(responses).isEmpty();
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
}
