package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.GlAccountRequest;
import com.expense_management_service.dto.response.GlAccountResponse;
import com.expense_management_service.service.GlAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/gl-accounts")
@RequiredArgsConstructor
public class GlAccountController {

    private final GlAccountService glAccountService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<GlAccountResponse> create(@Valid @RequestBody GlAccountRequest request) {
        return ApiResponse.success("GL account created", glAccountService.create(request));
    }

    @PutMapping("/{glAccountId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<GlAccountResponse> update(@PathVariable UUID glAccountId, @Valid @RequestBody GlAccountRequest request) {
        return ApiResponse.success("GL account updated", glAccountService.update(glAccountId, request));
    }

    @GetMapping("/{glAccountId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<GlAccountResponse> getById(@PathVariable UUID glAccountId) {
        return ApiResponse.success(glAccountService.getById(glAccountId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<List<GlAccountResponse>> getAll() {
        return ApiResponse.success(glAccountService.getAll());
    }

    @GetMapping("/active")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<List<GlAccountResponse>> getActive() {
        return ApiResponse.success(glAccountService.getActiveAccounts());
    }

    @DeleteMapping("/{glAccountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID glAccountId) {
        glAccountService.delete(glAccountId);
    }
}
