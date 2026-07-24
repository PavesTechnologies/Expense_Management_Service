package com.expense_management_service.controller;

import java.time.LocalDate;
import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ExchangeRateRequest;
import com.expense_management_service.dto.response.ExchangeRateRefreshResponse;
import com.expense_management_service.dto.response.ExchangeRateResponse;
import com.expense_management_service.service.ExchangeRateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/xms/admin/exchange-rates")
@RequiredArgsConstructor
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ExchangeRateResponse> create(@Valid @RequestBody ExchangeRateRequest request) {
        return ApiResponse.success("Exchange rate created", exchangeRateService.create(request));
    }

    @PutMapping("/{exchangeRateId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ExchangeRateResponse> update(@PathVariable UUID exchangeRateId,
                                                     @Valid @RequestBody ExchangeRateRequest request) {
        return ApiResponse.success("Exchange rate updated", exchangeRateService.update(exchangeRateId, request));
    }

    @GetMapping("/{exchangeRateId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<ExchangeRateResponse> getById(@PathVariable UUID exchangeRateId) {
        return ApiResponse.success(exchangeRateService.getById(exchangeRateId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<List<ExchangeRateResponse>> getAll(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID from,
            @RequestParam(required = false) UUID to) {
        if (date == null && from == null && to == null) {
            return ApiResponse.success(exchangeRateService.getAll());
        }
        return ApiResponse.success(exchangeRateService.getFiltered(date, from, to));
    }

    @PostMapping("/refresh")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ExchangeRateRefreshResponse> refresh() {
        return ApiResponse.success("Exchange rate refresh executed", exchangeRateService.refreshRates());
    }

    @DeleteMapping("/{exchangeRateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID exchangeRateId) {
        exchangeRateService.delete(exchangeRateId);
    }
}
