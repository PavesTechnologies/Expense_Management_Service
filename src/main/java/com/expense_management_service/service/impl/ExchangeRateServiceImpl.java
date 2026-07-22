package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ExchangeRateRequest;
import com.expense_management_service.dto.response.ExchangeRateResponse;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExchangeRate;
import com.expense_management_service.mapper.ExchangeRateMapper;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExchangeRateRepository;
import com.expense_management_service.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ExchangeRateServiceImpl implements ExchangeRateService {

    private final ExchangeRateRepository exchangeRateRepository;
    private final CurrencyRepository currencyRepository;
    private final ExchangeRateMapper exchangeRateMapper;

    @Override
    public ExchangeRateResponse create(ExchangeRateRequest request) {
        validateDifferentCurrencies(request);
        ExchangeRate entity = exchangeRateMapper.toEntity(request);
        entity.setFromCurrency(findCurrency(request.fromCurrencyId()));
        entity.setToCurrency(findCurrency(request.toCurrencyId()));
        return exchangeRateMapper.toResponse(exchangeRateRepository.save(entity));
    }

    @Override
    public ExchangeRateResponse update(UUID exchangeRateId, ExchangeRateRequest request) {
        validateDifferentCurrencies(request);
        ExchangeRate entity = findEntity(exchangeRateId);
        exchangeRateMapper.updateEntity(entity, request);
        entity.setFromCurrency(findCurrency(request.fromCurrencyId()));
        entity.setToCurrency(findCurrency(request.toCurrencyId()));
        return exchangeRateMapper.toResponse(exchangeRateRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public ExchangeRateResponse getById(UUID exchangeRateId) {
        return exchangeRateMapper.toResponse(findEntity(exchangeRateId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ExchangeRateResponse> getAll(Pageable pageable) {
        return exchangeRateRepository.findAll(pageable).map(exchangeRateMapper::toResponse);
    }

    @Override
    public void delete(UUID exchangeRateId) {
        exchangeRateRepository.delete(findEntity(exchangeRateId));
    }

    private void validateDifferentCurrencies(ExchangeRateRequest request) {
        if (request.fromCurrencyId().equals(request.toCurrencyId())) {
            throw new IllegalArgumentException("fromCurrencyId and toCurrencyId must be different");
        }
    }

    private Currency findCurrency(UUID currencyId) {
        return currencyRepository.findById(currencyId)
                .orElseThrow(() -> new ResourceNotFoundException("Currency not found with id: " + currencyId));
    }

    private ExchangeRate findEntity(UUID exchangeRateId) {
        return exchangeRateRepository.findById(exchangeRateId)
                .orElseThrow(() -> new ResourceNotFoundException("ExchangeRate not found with id: " + exchangeRateId));
    }
}
