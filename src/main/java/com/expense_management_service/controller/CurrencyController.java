package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.CurrencyRequest;
import com.expense_management_service.dto.response.CurrencyResponse;
import com.expense_management_service.service.CurrencyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/currencies")
@RequiredArgsConstructor
public class CurrencyController {

    private final CurrencyService currencyService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CurrencyResponse> create(@Valid @RequestBody CurrencyRequest request) {
        return ApiResponse.success("Currency created", currencyService.create(request));
    }

    @PutMapping("/{currencyId}")
    public ApiResponse<CurrencyResponse> update(@PathVariable UUID currencyId, @Valid @RequestBody CurrencyRequest request) {
        return ApiResponse.success("Currency updated", currencyService.update(currencyId, request));
    }

    @GetMapping("/{currencyId}")
    public ApiResponse<CurrencyResponse> getById(@PathVariable UUID currencyId) {
        return ApiResponse.success(currencyService.getById(currencyId));
    }

    @GetMapping
    public ApiResponse<Page<CurrencyResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(currencyService.getAll(pageable));
    }

    @DeleteMapping("/{currencyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID currencyId) {
        currencyService.delete(currencyId);
    }
}
