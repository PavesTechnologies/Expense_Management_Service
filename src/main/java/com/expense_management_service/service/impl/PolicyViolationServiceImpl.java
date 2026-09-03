package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.PolicyJustificationRequest;
import com.expense_management_service.dto.response.PolicyWarningResponse;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.PolicyViolation;
import com.expense_management_service.mapper.PolicyViolationMapper;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.PolicyViolationRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.RoleConstants;
import com.expense_management_service.service.PolicyViolationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PolicyViolationServiceImpl implements PolicyViolationService {

    private final ExpenseReportRepository expenseReportRepository;
    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final PolicyViolationRepository policyViolationRepository;
    private final PolicyViolationMapper policyViolationMapper;
    private final CurrentUserService currentUserService;

    /** Mirrors expense-report.business-purpose.min-length's convention: dynamically configurable, never a blocking rule by itself. */
    @Value("${policy.justification.min-length:20}")
    private int justificationMinLength;

    @Override
    @Transactional(readOnly = true)
    public List<PolicyWarningResponse> getForLineItem(UUID reportId, UUID lineItemId) {
        ExpenseReport report = findReport(reportId);
        assertViewable(report);
        ExpenseLineItem lineItem = findLineItem(reportId, lineItemId);
        return policyViolationRepository.findByLineItem_LineItemId(lineItem.getLineItemId()).stream()
                .map(policyViolationMapper::toResponse)
                .toList();
    }

    @Override
    public PolicyWarningResponse justify(UUID reportId, UUID lineItemId, UUID violationId, PolicyJustificationRequest request) {
        ExpenseReport report = findReport(reportId);
        assertOwnerOrAdmin(report);
        assertReportEditable(report);
        ExpenseLineItem lineItem = findLineItem(reportId, lineItemId);
        assertJustificationLongEnough(request.justification());

        PolicyViolation violation = policyViolationRepository.findByViolationIdAndLineItem_LineItemId(violationId, lineItem.getLineItemId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PolicyViolation not found with id: " + violationId + " on line item " + lineItemId));

        violation.setJustification(request.justification().trim());
        violation.setJustifiedAt(LocalDateTime.now());
        PolicyViolation saved = policyViolationRepository.save(violation);
        log.info("Justification recorded for policy violation {} on line item {}", violationId, lineItemId);
        return policyViolationMapper.toResponse(saved);
    }

    private void assertJustificationLongEnough(String justification) {
        if (justification == null || justification.trim().length() < justificationMinLength) {
            throw new IllegalArgumentException("justification must be at least " + justificationMinLength + " characters long");
        }
    }

    private void assertOwnerOrAdmin(ExpenseReport report) {
        CurrentUser caller = currentUserService.getCurrentUser();
        if (hasRole(caller, RoleConstants.ADMIN)) {
            return;
        }
        if (!report.getEmployeeId().equals(caller.employeeId())) {
            throw new AccessDeniedException("You can only justify policy warnings on your own expense report");
        }
    }

    private void assertViewable(ExpenseReport report) {
        CurrentUser caller = currentUserService.getCurrentUser();
        boolean privileged = hasRole(caller, RoleConstants.ADMIN) || hasRole(caller, RoleConstants.FINANCE)
                || hasRole(caller, RoleConstants.MANAGER) || hasRole(caller, RoleConstants.FINANCE_EXECUTIVE)
                || hasRole(caller, RoleConstants.AP_EXECUTIVE);
        if (privileged) {
            return;
        }
        if (!report.getEmployeeId().equals(caller.employeeId())) {
            throw new AccessDeniedException("You can only view policy warnings on your own expense report");
        }
    }

    private boolean hasRole(CurrentUser caller, String role) {
        return caller.roles() != null && caller.roles().stream().anyMatch(r -> r.equalsIgnoreCase(role));
    }

    private void assertReportEditable(ExpenseReport report) {
        if (!report.getReportStatus().isEditable()) {
            throw new BusinessRuleViolationException(
                    "Policy warnings cannot be justified while the report is in status " + report.getReportStatus());
        }
    }

    private ExpenseReport findReport(UUID reportId) {
        return expenseReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseReport not found with id: " + reportId));
    }

    private ExpenseLineItem findLineItem(UUID reportId, UUID lineItemId) {
        return expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ExpenseLineItem not found with id: " + lineItemId + " on report " + reportId));
    }
}
