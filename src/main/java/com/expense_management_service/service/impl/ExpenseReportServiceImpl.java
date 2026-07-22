package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ExpenseReportRequest;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.mapper.ExpenseReportMapper;
import com.expense_management_service.repository.CostCenterRepository;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.service.ExpenseReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ExpenseReportServiceImpl implements ExpenseReportService {

    private final ExpenseReportRepository expenseReportRepository;
    private final CostCenterRepository costCenterRepository;
    private final CurrencyRepository currencyRepository;
    private final ExpenseReportMapper expenseReportMapper;

    @Override
    public ExpenseReportResponse create(ExpenseReportRequest request) {
        ExpenseReport entity = expenseReportMapper.toEntity(request);
        entity.setCostCenter(findCostCenter(request.costCenterId()));
        entity.setCurrency(findCurrency(request.currencyId()));
        return expenseReportMapper.toResponse(expenseReportRepository.save(entity));
    }

    @Override
    public ExpenseReportResponse update(UUID reportId, ExpenseReportRequest request) {
        ExpenseReport entity = findEntity(reportId);
        expenseReportMapper.updateEntity(entity, request);
        entity.setCostCenter(findCostCenter(request.costCenterId()));
        entity.setCurrency(findCurrency(request.currencyId()));
        return expenseReportMapper.toResponse(expenseReportRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public ExpenseReportResponse getById(UUID reportId) {
        return expenseReportMapper.toResponse(findEntity(reportId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ExpenseReportResponse> getAll(Pageable pageable) {
        return expenseReportRepository.findAll(pageable).map(expenseReportMapper::toResponse);
    }

    @Override
    public void delete(UUID reportId) {
        expenseReportRepository.delete(findEntity(reportId));
    }

    private CostCenter findCostCenter(UUID costCenterId) {
        return costCenterRepository.findById(costCenterId)
                .orElseThrow(() -> new ResourceNotFoundException("CostCenter not found with id: " + costCenterId));
    }

    private Currency findCurrency(UUID currencyId) {
        return currencyRepository.findById(currencyId)
                .orElseThrow(() -> new ResourceNotFoundException("Currency not found with id: " + currencyId));
    }

    private ExpenseReport findEntity(UUID reportId) {
        return expenseReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseReport not found with id: " + reportId));
    }
}
