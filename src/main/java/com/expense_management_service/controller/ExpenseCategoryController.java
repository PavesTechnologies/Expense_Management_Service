package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ExpenseCategoryRequest;
import com.expense_management_service.dto.response.ExpenseCategoryResponse;
import com.expense_management_service.service.ExpenseCategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/expense-categories")
@RequiredArgsConstructor
public class ExpenseCategoryController {

    private final ExpenseCategoryService expenseCategoryService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ExpenseCategoryResponse> create(@Valid @RequestBody ExpenseCategoryRequest request) {
        return ApiResponse.success("Expense category created", expenseCategoryService.create(request));
    }

    @PutMapping("/{categoryId}")
    public ApiResponse<ExpenseCategoryResponse> update(@PathVariable UUID categoryId,
                                                        @Valid @RequestBody ExpenseCategoryRequest request) {
        return ApiResponse.success("Expense category updated", expenseCategoryService.update(categoryId, request));
    }

    @GetMapping("/{categoryId}")
    public ApiResponse<ExpenseCategoryResponse> getById(@PathVariable UUID categoryId) {
        return ApiResponse.success(expenseCategoryService.getById(categoryId));
    }

    @GetMapping
    public ApiResponse<Page<ExpenseCategoryResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(expenseCategoryService.getAll(pageable));
    }

    @DeleteMapping("/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID categoryId) {
        expenseCategoryService.delete(categoryId);
    }
}
