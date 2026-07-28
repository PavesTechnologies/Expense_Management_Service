package com.expense_management_service.service;

import com.expense_management_service.dto.response.ApprovalTaskResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.dto.response.PolicyWarningResponse;

import java.util.List;
import java.util.UUID;

/**
 * Owns every approval state transition (EP06 plan, Phase 2). Unlike the ~20 other services in
 * this codebase, these changes have cross-entity effects and must not be spread across the
 * individual CRUD services for ExpenseReport/ApprovalTask/ApprovalMatrix.
 */
public interface ApprovalWorkflowService {

    /**
     * Resolves the approval matrix for the report's cost center and (base-currency-converted)
     * amount, materialises the full chain as ApprovalTask rows in one transaction (the
     * "snapshot"), activates the first eligible level, and moves the report to PENDING_APPROVAL.
     */
    ExpenseReportResponse submit(UUID reportId);

    /** Approves a PENDING task assigned to {@code actingEmployeeId}; advances the chain if the level completes. */
    ApprovalTaskResponse approve(UUID taskId, String actingEmployeeId, String comments);

    /** Rejects a PENDING task; cancels the rest of the chain and reverts the report to DRAFT. */
    ApprovalTaskResponse reject(UUID taskId, String actingEmployeeId, String comments);

    /** PENDING tasks currently assigned to the given approver. */
    List<ApprovalTaskResponse> getMyQueue(String employeeId);

    /** EP05: every policy warning (with justification, if any) recorded against the task's report — an approver drill-down. */
    List<PolicyWarningResponse> getPolicyWarningsForTask(UUID taskId);
}
