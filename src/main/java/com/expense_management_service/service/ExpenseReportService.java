package com.expense_management_service.service;

import com.expense_management_service.dto.request.ExpenseReportRequest;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import java.util.List;
import com.expense_management_service.dto.response.PageResponse;
import org.springframework.data.domain.Pageable;
import java.util.UUID;

/**
 * Create-and-save-Draft expense report business logic (EP02-S1).
 * <p>
 * Ownership and status-gating are enforced inside the implementation, not exposed as
 * parameters here: the caller is always taken from the security context, never a request
 * argument, so these methods cannot be used to act on another employee's report.
 */
public interface ExpenseReportService {

    /** Always created in Draft, owned by the caller, with a server-generated report number and fiscal year. */
    ExpenseReportResponse create(ExpenseReportRequest request);

    /** Only the owning employee (or an Admin) may update, and only while the report is in an editable status. */
    ExpenseReportResponse update(UUID reportId, ExpenseReportRequest request);

    /** Employees may only fetch their own reports; Admin/Finance/Manager may fetch any. */
    ExpenseReportResponse getById(UUID reportId);

    /** Returns the caller's own reports as a sorted, paginated expense history. */
    PageResponse<ExpenseReportResponse> getAll(Pageable pageable);

    /** Returns the caller's own reports filtered by status and/or free-text search. */
    default PageResponse<ExpenseReportResponse> getAll(Pageable pageable, String status, String search) {
        return getAll(pageable);
    }

    /** Only the owning employee (or an Admin) may delete, and only while the report is still a Draft. */
    void delete(UUID reportId);
}
