package com.expense_management_service.service;

import com.expense_management_service.dto.request.ExpenseReportRequest;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import java.util.List;
import java.util.UUID;

public interface ExpenseReportService {

    ExpenseReportResponse create(ExpenseReportRequest request);

    ExpenseReportResponse update(UUID reportId, ExpenseReportRequest request);

    ExpenseReportResponse getById(UUID reportId);

    List<ExpenseReportResponse> getAll();

    void delete(UUID reportId);
}
