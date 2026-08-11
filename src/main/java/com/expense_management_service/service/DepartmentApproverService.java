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
}
