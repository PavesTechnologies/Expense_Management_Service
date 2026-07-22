package com.expense_management_service.service;

import com.expense_management_service.dto.request.ExchangeRateRequest;
import com.expense_management_service.dto.response.ExchangeRateResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ExchangeRateService {

    ExchangeRateResponse create(ExchangeRateRequest request);

    ExchangeRateResponse update(UUID exchangeRateId, ExchangeRateRequest request);

    ExchangeRateResponse getById(UUID exchangeRateId);

    Page<ExchangeRateResponse> getAll(Pageable pageable);

    void delete(UUID exchangeRateId);
}
