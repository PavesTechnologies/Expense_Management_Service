package com.expense_management_service.service;

import com.expense_management_service.dto.response.AllocationContextResponse;
import com.expense_management_service.entity.ExpenseReport;

import java.util.List;
import java.util.Optional;

/**
 * Expenses are charged to the cost center(s) of the business purpose they served, which may be
 * outside the paying employee's home department (one employee pays a team lunch for Engineering
 * and HR, split across both). That is allowed, but each such line needs a short reason so
 * approvers and Finance can tell a genuine cross-department charge from a mistake.
 */
public interface CrossDepartmentAllocationService {

    /** The employee's home department UUID from the employee directory, if known. */
    Optional<String> homeDepartmentOf(String employeeId);

    /** The authenticated caller's allocation context, for the expense form. */
    AllocationContextResponse getAllocationContextForCurrentUser();

    /**
     * One employee-facing message per line item charged (directly or through its splits) to a
     * cost center outside the submitter's home department without an allocation reason. Empty
     * when the home department, or every cost center's department, is unknown.
     */
    List<String> findLinesMissingReason(ExpenseReport report);
}
