package com.expense_management_service.service;

import com.expense_management_service.dto.response.AssignedProjectResponse;

import java.util.List;

/**
 * Project selection for client-billable expense submission (Epic 8) — backs the "select
 * project" step of the employee submission flow. Read-only: it never changes an expense; see
 * {@code ExpenseLineItemService} for the actual save path, which re-validates the selected
 * project against PMS independently rather than trusting this list.
 */
public interface EmployeeProjectService {

    /** PMS-assigned active projects for the authenticated caller, upserted into the local cache. */
    List<AssignedProjectResponse> getAssignedProjects();
}
