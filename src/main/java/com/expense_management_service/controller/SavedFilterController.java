package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.SavedFilterRequest;
import com.expense_management_service.dto.response.SavedFilterResponse;
import com.expense_management_service.service.SavedFilterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/xms/employee/saved-filters")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
public class SavedFilterController {

    private final SavedFilterService savedFilterService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SavedFilterResponse> create(@Valid @RequestBody SavedFilterRequest request) {
        return ApiResponse.success("Saved filter created", savedFilterService.create(request));
    }

    @PutMapping("/{filterId}")
    public ApiResponse<SavedFilterResponse> update(@PathVariable UUID filterId, @Valid @RequestBody SavedFilterRequest request) {
        return ApiResponse.success("Saved filter updated", savedFilterService.update(filterId, request));
    }

    @GetMapping("/{filterId}")
    public ApiResponse<SavedFilterResponse> getById(@PathVariable UUID filterId) {
        return ApiResponse.success(savedFilterService.getById(filterId));
    }

    @GetMapping
    public ApiResponse<List<SavedFilterResponse>> getAll() {
        return ApiResponse.success(savedFilterService.getAll());
    }

    @DeleteMapping("/{filterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID filterId) {
        savedFilterService.delete(filterId);
    }
}
