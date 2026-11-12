package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ApproverExceptionRequest;
import com.expense_management_service.dto.request.AuditLogRequest;
import com.expense_management_service.dto.request.PolicyJustificationRequest;
import com.expense_management_service.dto.response.PolicyWarningResponse;
import com.expense_management_service.entity.ApprovalAssignment;
import com.expense_management_service.entity.ApprovalLevelInstance;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.PolicyViolation;
import com.expense_management_service.enums.AssignmentStatus;
import com.expense_management_service.enums.LevelInstanceStatus;
import com.expense_management_service.enums.LevelType;
import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.mapper.PolicyViolationMapper;
import com.expense_management_service.repository.ApprovalAssignmentRepository;
import com.expense_management_service.repository.ApprovalLevelInstanceRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.PolicyViolationRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.RoleConstants;
import com.expense_management_service.service.AuditLogService;
import com.expense_management_service.service.DelegationService;
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

    /** Mirrors {@code ExpenseLineItemServiceImpl}'s line-item status values. */
    private static final String LINE_STATUS_ACTIVE = "ACTIVE";
    private static final String LINE_STATUS_BLOCKED = "BLOCKED";

    private final ExpenseReportRepository expenseReportRepository;
    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final PolicyViolationRepository policyViolationRepository;
    private final PolicyViolationMapper policyViolationMapper;
    private final CurrentUserService currentUserService;
    private final ApprovalLevelInstanceRepository approvalLevelInstanceRepository;
    private final ApprovalAssignmentRepository approvalAssignmentRepository;
    private final DelegationService delegationService;
    private final AuditLogService auditLogService;

    /** Mirrors expense-report.business-purpose.min-length's convention: dynamically configurable, never a blocking rule by itself. */
    @Value("${policy.justification.min-length:20}")
    private int justificationMinLength;

    private static final String REDACTED_CROSS_EMPLOYEE_MESSAGE =
            "Another employee submitted a matching expense (same vendor, date, amount, and currency) — under Finance review";

    @Override
    @Transactional(readOnly = true)
    public List<PolicyWarningResponse> getForLineItem(UUID reportId, UUID lineItemId) {
        ExpenseReport report = findReport(reportId);
        assertViewable(report);
        ExpenseLineItem lineItem = findLineItem(reportId, lineItemId);
        boolean privileged = isPrivileged(currentUserService.getCurrentUser());
        return policyViolationRepository.findByLineItem_LineItemId(lineItem.getLineItemId()).stream()
                .map(policyViolationMapper::toResponse)
                .map(response -> redactIfNeeded(response, privileged))
                .toList();
    }

    /**
     * A {@code CROSS_EMPLOYEE_DUPLICATE_EXPENSE} violation's message names the matching employee id
     * and their line item — useful for Finance/Admin/Manager to investigate, but not appropriate to
     * expose to the report's own owner (a plain employee), who has no legitimate need to know who
     * else submitted a similar-looking expense. The full detail stays in the stored entity; only the
     * response given to a non-privileged viewer is redacted.
     */
    private PolicyWarningResponse redactIfNeeded(PolicyWarningResponse response, boolean privileged) {
        if (privileged || response.ruleType() != PolicyRuleType.CROSS_EMPLOYEE_DUPLICATE_EXPENSE) {
            return response;
        }
        return new PolicyWarningResponse(
                response.violationId(), response.ruleType(), response.severity(), response.enforcementType(),
                REDACTED_CROSS_EMPLOYEE_MESSAGE, response.limitValue(), response.actualValue(), response.overagePercent(),
                response.severityTier(), response.currencyCode(), response.justification(), response.justifiedAt(),
                response.justifiedBy(), response.approverJustification(), response.approverJustifiedBy(),
                response.approverJustifiedAt(), response.policyVersionNumber());
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

        String actingEmployeeId = currentUserService.getCurrentUser().employeeId();
        String previousJustification = violation.getJustification();
        violation.setJustification(request.justification().trim());
        violation.setJustifiedAt(LocalDateTime.now());
        violation.setJustifiedBy(actingEmployeeId);
        PolicyViolation saved = policyViolationRepository.save(violation);
        // An explained BLOCK violation no longer holds the line item back: the approver decides.
        lineItem.setLineStatus(PolicyViolation.blocksLineItem(
                policyViolationRepository.findByLineItem_LineItemId(lineItem.getLineItemId())) ? LINE_STATUS_BLOCKED : LINE_STATUS_ACTIVE);
        auditLogService.create(new AuditLogRequest("PolicyViolation", violationId, "JUSTIFIED",
                previousJustification, request.justification().trim(), actingEmployeeId));
        log.info("Justification recorded for policy violation {} on line item {}", violationId, lineItemId);
        return policyViolationMapper.toResponse(saved);
    }

    @Override
    public PolicyWarningResponse approveException(UUID reportId, UUID lineItemId, UUID violationId, String actingApproverId, ApproverExceptionRequest request) {
        assertJustificationLongEnough(request.justification());
        ExpenseLineItem lineItem = findLineItem(reportId, lineItemId);

        PolicyViolation violation = policyViolationRepository.findByViolationIdAndLineItem_LineItemId(violationId, lineItem.getLineItemId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PolicyViolation not found with id: " + violationId + " on line item " + lineItemId));
        if (violation.getApproverJustifiedAt() != null) {
            throw new IllegalArgumentException("This violation was already authorized as an exception by "
                    + violation.getApproverJustifiedBy() + " at " + violation.getApproverJustifiedAt());
        }

        ApprovalAssignment authorizing = assertActiveApproverOrDelegate(reportId, actingApproverId);
        log.debug("Exception on violation {} authorized via assignment {}", violationId, authorizing.getAssignmentId());

        violation.setApproverJustification(request.justification().trim());
        violation.setApproverJustifiedBy(actingApproverId);
        violation.setApproverJustifiedAt(LocalDateTime.now());
        PolicyViolation saved = policyViolationRepository.save(violation);
        auditLogService.create(new AuditLogRequest("PolicyViolation", violationId, "EXCEPTION_APPROVED",
                null, request.justification().trim(), actingApproverId));
        log.info("Exception approved for policy violation {} on line item {} by {}", violationId, lineItemId, actingApproverId);
        return policyViolationMapper.toResponse(saved);
    }

    /**
     * Mirrors {@code ApprovalWorkflowServiceImpl.reviewLineItem}'s authorization check exactly: the
     * report must have an ACTIVE level instance whose type is APPROVAL (not FINANCE_VERIFICATION),
     * and the caller (or their active delegate) must hold an ACTIVE assignment on it - excluding
     * split-owner-only assignments, which exist only to track a Cost Center Owner's own splits, never
     * as a stand-in completer for the shared line-item-level action an exception authorization is.
     */
    private ApprovalAssignment assertActiveApproverOrDelegate(UUID reportId, String actingApproverId) {
        int cycle = approvalLevelInstanceRepository.findMaxSubmissionCycle(reportId);
        ApprovalLevelInstance activeInstance = approvalLevelInstanceRepository
                .findByReport_ReportIdAndSubmissionCycleAndStatus(reportId, cycle, LevelInstanceStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("Report " + reportId + " has no level currently active for review"));
        if (activeInstance.getLevelType() != LevelType.APPROVAL) {
            throw new IllegalArgumentException("Report " + reportId + "'s active level is a Finance Verification level - "
                    + "policy exceptions can only be authorized at an Approval level");
        }

        return approvalAssignmentRepository.findByLevelInstance_InstanceId(activeInstance.getInstanceId())
                .stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE)
                .filter(a -> a.getSplitReviews().isEmpty())
                .filter(a -> delegationService.canAct(actingApproverId, a.getApproverId()))
                .findFirst()
                .orElseThrow(() -> new AccessDeniedException(
                        "You are not an active approver (or delegate) for this report's current level"));
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
        if (isPrivileged(caller)) {
            return;
        }
        if (!report.getEmployeeId().equals(caller.employeeId())) {
            throw new AccessDeniedException("You can only view policy warnings on your own expense report");
        }
    }

    private boolean isPrivileged(CurrentUser caller) {
        return hasRole(caller, RoleConstants.ADMIN) || hasRole(caller, RoleConstants.FINANCE)
                || hasRole(caller, RoleConstants.MANAGER) || hasRole(caller, RoleConstants.FINANCE_EXECUTIVE)
                || hasRole(caller, RoleConstants.AP_EXECUTIVE);
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
