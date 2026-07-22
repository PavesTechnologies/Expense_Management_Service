package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.CashAdvanceRequest;
import com.expense_management_service.dto.response.CashAdvanceResponse;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.mapper.CashAdvanceMapper;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.service.CashAdvanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class CashAdvanceServiceImpl implements CashAdvanceService {

    private final CashAdvanceRepository cashAdvanceRepository;
    private final CurrencyRepository currencyRepository;
    private final CashAdvanceMapper cashAdvanceMapper;

    @Override
    public CashAdvanceResponse create(CashAdvanceRequest request) {
        CashAdvance entity = cashAdvanceMapper.toEntity(request);
        entity.setCurrency(findCurrency(request.currencyId()));
        return cashAdvanceMapper.toResponse(cashAdvanceRepository.save(entity));
    }

    @Override
    public CashAdvanceResponse update(UUID advanceId, CashAdvanceRequest request) {
        CashAdvance entity = findEntity(advanceId);
        cashAdvanceMapper.updateEntity(entity, request);
        entity.setCurrency(findCurrency(request.currencyId()));
        return cashAdvanceMapper.toResponse(cashAdvanceRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public CashAdvanceResponse getById(UUID advanceId) {
        return cashAdvanceMapper.toResponse(findEntity(advanceId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CashAdvanceResponse> getAll(Pageable pageable) {
        return cashAdvanceRepository.findAll(pageable).map(cashAdvanceMapper::toResponse);
    }

    @Override
    public void delete(UUID advanceId) {
        cashAdvanceRepository.delete(findEntity(advanceId));
    }

    private Currency findCurrency(UUID currencyId) {
        return currencyRepository.findById(currencyId)
                .orElseThrow(() -> new ResourceNotFoundException("Currency not found with id: " + currencyId));
    }

    private CashAdvance findEntity(UUID advanceId) {
        return cashAdvanceRepository.findById(advanceId)
                .orElseThrow(() -> new ResourceNotFoundException("CashAdvance not found with id: " + advanceId));
    }
}
