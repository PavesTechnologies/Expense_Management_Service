package com.expense_management_service.service;

import com.expense_management_service.dto.request.ExpenseReportRequest;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ExpenseReportService {

    ExpenseReportResponse create(ExpenseReportRequest request);

    ExpenseReportResponse update(UUID reportId, ExpenseReportRequest request);

    ExpenseReportResponse getById(UUID reportId);

    Page<ExpenseReportResponse> getAll(Pageable pageable);

    void delete(UUID reportId);
}
