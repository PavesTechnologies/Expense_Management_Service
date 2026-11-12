package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ExpenseCategoryTaxMappingRequest;
import com.expense_management_service.dto.response.ExpenseCategoryTaxMappingResponse;
import com.expense_management_service.service.ExpenseCategoryTaxMappingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Dated category -> tax code mappings. Global tax configuration: written by ADMIN only. */
@RestController
@RequestMapping("/xms/admin/expense-categories/{categoryId}/tax-mappings")
@RequiredArgsConstructor
public class ExpenseCategoryTaxMappingController {

    private final ExpenseCategoryTaxMappingService mappingService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','FINANCE_EXECUTIVE','MANAGER')")
    public ApiResponse<List<ExpenseCategoryTaxMappingResponse>> list(@PathVariable UUID categoryId) {
        return ApiResponse.success(mappingService.list(categoryId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ExpenseCategoryTaxMappingResponse> create(@PathVariable UUID categoryId,
                                                                 @Valid @RequestBody ExpenseCategoryTaxMappingRequest request) {
        return ApiResponse.success("Tax mapping added", mappingService.create(categoryId, request));
    }

    @PutMapping("/{mappingId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ExpenseCategoryTaxMappingResponse> update(@PathVariable UUID categoryId, @PathVariable UUID mappingId,
                                                                 @Valid @RequestBody ExpenseCategoryTaxMappingRequest request) {
        return ApiResponse.success("Tax mapping updated", mappingService.update(categoryId, mappingId, request));
    }

    @DeleteMapping("/{mappingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID categoryId, @PathVariable UUID mappingId) {
        mappingService.delete(categoryId, mappingId);
    }
}
