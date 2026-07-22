package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.VerificationQueryRequest;
import com.expense_management_service.dto.response.VerificationQueryResponse;
import com.expense_management_service.service.VerificationQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/verification-queries")
@RequiredArgsConstructor
public class VerificationQueryController {

    private final VerificationQueryService verificationQueryService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<VerificationQueryResponse> create(@Valid @RequestBody VerificationQueryRequest request) {
        return ApiResponse.success("Verification query created", verificationQueryService.create(request));
    }

    @PutMapping("/{queryId}")
    public ApiResponse<VerificationQueryResponse> update(@PathVariable UUID queryId,
                                                          @Valid @RequestBody VerificationQueryRequest request) {
        return ApiResponse.success("Verification query updated", verificationQueryService.update(queryId, request));
    }

    @GetMapping("/{queryId}")
    public ApiResponse<VerificationQueryResponse> getById(@PathVariable UUID queryId) {
        return ApiResponse.success(verificationQueryService.getById(queryId));
    }

    @GetMapping
    public ApiResponse<Page<VerificationQueryResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(verificationQueryService.getAll(pageable));
    }

    @DeleteMapping("/{queryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID queryId) {
        verificationQueryService.delete(queryId);
    }
}
