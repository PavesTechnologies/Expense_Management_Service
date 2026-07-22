package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ExpenseReportRequest;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.service.ExpenseReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/expense-reports")
@RequiredArgsConstructor
public class ExpenseReportController {

    private final ExpenseReportService expenseReportService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ExpenseReportResponse> create(@Valid @RequestBody ExpenseReportRequest request) {
        return ApiResponse.success("Expense report created", expenseReportService.create(request));
    }

    @PutMapping("/{reportId}")
    public ApiResponse<ExpenseReportResponse> update(@PathVariable UUID reportId,
                                                      @Valid @RequestBody ExpenseReportRequest request) {
        return ApiResponse.success("Expense report updated", expenseReportService.update(reportId, request));
    }

    @GetMapping("/{reportId}")
    public ApiResponse<ExpenseReportResponse> getById(@PathVariable UUID reportId) {
        return ApiResponse.success(expenseReportService.getById(reportId));
    }

    @GetMapping
    public ApiResponse<Page<ExpenseReportResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(expenseReportService.getAll(pageable));
    }

    @DeleteMapping("/{reportId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID reportId) {
        expenseReportService.delete(reportId);
    }
}
