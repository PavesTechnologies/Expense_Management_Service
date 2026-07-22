package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.GlAccountRequest;
import com.expense_management_service.dto.response.GlAccountResponse;
import com.expense_management_service.service.GlAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/gl-accounts")
@RequiredArgsConstructor
public class GlAccountController {

    private final GlAccountService glAccountService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<GlAccountResponse> create(@Valid @RequestBody GlAccountRequest request) {
        return ApiResponse.success("GL account created", glAccountService.create(request));
    }

    @PutMapping("/{glAccountId}")
    public ApiResponse<GlAccountResponse> update(@PathVariable UUID glAccountId, @Valid @RequestBody GlAccountRequest request) {
        return ApiResponse.success("GL account updated", glAccountService.update(glAccountId, request));
    }

    @GetMapping("/{glAccountId}")
    public ApiResponse<GlAccountResponse> getById(@PathVariable UUID glAccountId) {
        return ApiResponse.success(glAccountService.getById(glAccountId));
    }

    @GetMapping
    public ApiResponse<Page<GlAccountResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(glAccountService.getAll(pageable));
    }

    @DeleteMapping("/{glAccountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID glAccountId) {
        glAccountService.delete(glAccountId);
    }
}
