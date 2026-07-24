package com.expense_management_service.service;

import com.expense_management_service.dto.request.CurrencyRequest;
import com.expense_management_service.dto.response.CurrencyResponse;
import java.util.List;
import java.util.UUID;

public interface CurrencyService {

    CurrencyResponse create(CurrencyRequest request);

    CurrencyResponse update(UUID currencyId, CurrencyRequest request);

    CurrencyResponse getById(UUID currencyId);

    List<CurrencyResponse> getAll();

    void delete(UUID currencyId);
}
