package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ExpenseCategoryRequest;
import com.expense_management_service.dto.response.ExpenseCategoryResponse;
import com.expense_management_service.service.ExpenseCategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/xms/admin/expense-categories")
@RequiredArgsConstructor
public class ExpenseCategoryController {

    private final ExpenseCategoryService expenseCategoryService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ExpenseCategoryResponse> create(@Valid @RequestBody ExpenseCategoryRequest request) {
        return ApiResponse.success("Expense category created", expenseCategoryService.create(request));
    }

    @PutMapping("/{categoryId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ExpenseCategoryResponse> update(@PathVariable UUID categoryId,
                                                        @Valid @RequestBody ExpenseCategoryRequest request) {
        return ApiResponse.success("Expense category updated", expenseCategoryService.update(categoryId, request));
    }

    @GetMapping("/{categoryId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<ExpenseCategoryResponse> getById(@PathVariable UUID categoryId) {
        return ApiResponse.success(expenseCategoryService.getById(categoryId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<List<ExpenseCategoryResponse>> getAll() {
        return ApiResponse.success(expenseCategoryService.getAll());
    }

    @GetMapping("/active")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<List<ExpenseCategoryResponse>> getActive() {
        return ApiResponse.success(expenseCategoryService.getActiveCategories());
    }

    @DeleteMapping("/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    
    public void delete(@PathVariable UUID categoryId) {
        expenseCategoryService.delete(categoryId);
    }
}
