package com.expense_management_service.service;

import com.expense_management_service.dto.request.ExpenseCategoryRequest;
import com.expense_management_service.dto.response.ExpenseCategoryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ExpenseCategoryService {

    ExpenseCategoryResponse create(ExpenseCategoryRequest request);

    ExpenseCategoryResponse update(UUID categoryId, ExpenseCategoryRequest request);

    ExpenseCategoryResponse getById(UUID categoryId);

    Page<ExpenseCategoryResponse> getAll(Pageable pageable);

    void delete(UUID categoryId);
}
