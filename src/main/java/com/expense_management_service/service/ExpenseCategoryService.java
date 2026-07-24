package com.expense_management_service.service;

import com.expense_management_service.dto.request.ExpenseCategoryRequest;
import com.expense_management_service.dto.response.ExpenseCategoryResponse;
import java.util.List;
import java.util.UUID;

public interface ExpenseCategoryService {

    ExpenseCategoryResponse create(ExpenseCategoryRequest request);

    ExpenseCategoryResponse update(UUID categoryId, ExpenseCategoryRequest request);

    ExpenseCategoryResponse getById(UUID categoryId);

    List<ExpenseCategoryResponse> getAll();

    /** Active-only, name-ordered list for downstream pickers (e.g. the Expense Line Item form). */
    List<ExpenseCategoryResponse> getActiveCategories();

    void delete(UUID categoryId);
}
