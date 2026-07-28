package com.expense_management_service.integration.departments;

import com.expense_management_service.integration.departments.dto.DepartmentResponse;

import java.util.List;
import java.util.UUID;

/**
 * Read-only client for Department master data, which is owned by Employee Onboarding —
 * XMS never creates a local Department entity/table and never persists department names,
 * only the {@code departmentUuid} reference (see {@code CostCenter.departmentUuid}).
 */
public interface DepartmentClient {

    /** Fetches every department from Employee Onboarding's {@code GET /ems/masters/departments}. */
    List<DepartmentResponse> getAllDepartments();

    /** @return true if {@code departmentUuid} matches a department currently returned by Employee Onboarding. */
    boolean existsById(UUID departmentUuid);
}
