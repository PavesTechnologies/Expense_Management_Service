package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.VerificationQueryRequest;
import com.expense_management_service.dto.response.VerificationQueryResponse;
import com.expense_management_service.service.VerificationQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/xms/finance/verifications")
@RequiredArgsConstructor
public class VerificationQueryController {

    private final VerificationQueryService verificationQueryService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<VerificationQueryResponse> create(@Valid @RequestBody VerificationQueryRequest request) {
        return ApiResponse.success("Verification query created", verificationQueryService.create(request));
    }

    @PutMapping("/{queryId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER','GENERAL')")
    public ApiResponse<VerificationQueryResponse> update(@PathVariable UUID queryId,
                                                          @Valid @RequestBody VerificationQueryRequest request) {
        return ApiResponse.success("Verification query updated", verificationQueryService.update(queryId, request));
    }

    @GetMapping("/{queryId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER','GENERAL')")
    public ApiResponse<VerificationQueryResponse> getById(@PathVariable UUID queryId) {
        return ApiResponse.success(verificationQueryService.getById(queryId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER','GENERAL')")
    public ApiResponse<List<VerificationQueryResponse>> getAll() {
        return ApiResponse.success(verificationQueryService.getAll());
    }

    @DeleteMapping("/{queryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID queryId) {
        verificationQueryService.delete(queryId);
    }
}
