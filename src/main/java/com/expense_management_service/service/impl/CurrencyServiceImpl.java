package com.expense_management_service.service.impl;

import java.util.List;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.CurrencyRequest;
import com.expense_management_service.dto.response.CurrencyResponse;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.mapper.CurrencyMapper;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.service.CurrencyService;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class CurrencyServiceImpl implements CurrencyService {

    private final CurrencyRepository currencyRepository;
    private final CurrencyMapper currencyMapper;

    @Override
    public CurrencyResponse create(CurrencyRequest request) {
        Currency entity = currencyMapper.toEntity(request);
        return currencyMapper.toResponse(currencyRepository.save(entity));
    }

    @Override
    public CurrencyResponse update(UUID currencyId, CurrencyRequest request) {
        Currency entity = findEntity(currencyId);
        currencyMapper.updateEntity(entity, request);
        return currencyMapper.toResponse(currencyRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public CurrencyResponse getById(UUID currencyId) {
        return currencyMapper.toResponse(findEntity(currencyId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CurrencyResponse> getAll() {
        return currencyRepository.findAll().stream().map(currencyMapper::toResponse).toList();
    }

    @Override
    public void delete(UUID currencyId) {
        currencyRepository.delete(findEntity(currencyId));
    }

    private Currency findEntity(UUID currencyId) {
        return currencyRepository.findById(currencyId)
                .orElseThrow(() -> new ResourceNotFoundException("Currency not found with id: " + currencyId));
    }
}
