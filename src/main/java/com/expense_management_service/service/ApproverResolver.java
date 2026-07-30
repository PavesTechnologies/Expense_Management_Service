package com.expense_management_service.service;

import com.expense_management_service.entity.ApprovalMatrix;

import java.util.Optional;

/**
 * Resolves a single {@code ApprovalMatrix} row to a concrete approver employeeId.
 * <p>
 * Implementations are swapped via Spring bean wiring — {@code ApprovalWorkflowService} depends
 * only on this interface, mirroring the {@link ExchangeRateProvider}/{@code StubExchangeRateProvider}
 * pattern already used in this codebase. USER and COST_CENTER_OWNER are resolvable directly from
 * data already in this service; MANAGER depends on {@code EmployeeCache} (the Employee CDC
 * pipeline); ROLE has no backing data source yet (UMS exposes no list-users-by-role capability)
 * and falls back to a configured default approver.
 */
public interface ApproverResolver {

    /**
     * @param matrixRow            the resolved level's matrix row (carries approverType/approverReference/costCenter)
     * @param submittingEmployeeId the employeeId of the report's owner - needed for MANAGER resolution
     * @return the resolved approver's employeeId, or empty if it could not be resolved at all
     *         (no data source had an answer, and no default approver is configured)
     */
    Optional<String> resolve(ApprovalMatrix matrixRow, String submittingEmployeeId);
}
