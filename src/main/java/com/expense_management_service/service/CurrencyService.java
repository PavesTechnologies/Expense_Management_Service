package com.expense_management_service.service;

import com.expense_management_service.dto.request.CurrencyRequest;
import com.expense_management_service.dto.response.CurrencyResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface CurrencyService {

    CurrencyResponse create(CurrencyRequest request);

    CurrencyResponse update(UUID currencyId, CurrencyRequest request);

    CurrencyResponse getById(UUID currencyId);

    Page<CurrencyResponse> getAll(Pageable pageable);

    void delete(UUID currencyId);
}
