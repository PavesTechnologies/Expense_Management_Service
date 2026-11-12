package com.expense_management_service.service;

import com.expense_management_service.dto.request.DepartmentApproverRequest;
import com.expense_management_service.dto.response.DepartmentApproverResponse;

import java.util.List;
import java.util.UUID;

public interface DepartmentApproverService {

    DepartmentApproverResponse create(DepartmentApproverRequest request);

    DepartmentApproverResponse update(UUID departmentApproverId, DepartmentApproverRequest request);

    DepartmentApproverResponse getById(UUID departmentApproverId);

    List<DepartmentApproverResponse> getAll();

    void delete(UUID departmentApproverId);

    /** Every Employee Onboarding department with its configured approver (if any), by department name. */
    List<com.expense_management_service.dto.response.DepartmentApproverOverviewResponse> getDepartmentOverview();

    /** Active UMS users, matched to employee records, that can be picked as a department approver. */
    List<com.expense_management_service.dto.response.ApproverCandidateResponse> getApproverCandidates();
}
