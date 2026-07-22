package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ExchangeRateRequest;
import com.expense_management_service.dto.response.ExchangeRateResponse;
import com.expense_management_service.service.ExchangeRateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/exchange-rates")
@RequiredArgsConstructor
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ExchangeRateResponse> create(@Valid @RequestBody ExchangeRateRequest request) {
        return ApiResponse.success("Exchange rate created", exchangeRateService.create(request));
    }

    @PutMapping("/{exchangeRateId}")
    public ApiResponse<ExchangeRateResponse> update(@PathVariable UUID exchangeRateId,
                                                     @Valid @RequestBody ExchangeRateRequest request) {
        return ApiResponse.success("Exchange rate updated", exchangeRateService.update(exchangeRateId, request));
    }

    @GetMapping("/{exchangeRateId}")
    public ApiResponse<ExchangeRateResponse> getById(@PathVariable UUID exchangeRateId) {
        return ApiResponse.success(exchangeRateService.getById(exchangeRateId));
    }

    @GetMapping
    public ApiResponse<Page<ExchangeRateResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(exchangeRateService.getAll(pageable));
    }

    @DeleteMapping("/{exchangeRateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID exchangeRateId) {
        exchangeRateService.delete(exchangeRateId);
    }
}
