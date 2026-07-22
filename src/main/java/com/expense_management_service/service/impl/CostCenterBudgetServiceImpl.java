package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.CostCenterBudgetRequest;
import com.expense_management_service.dto.response.CostCenterBudgetResponse;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.CostCenterBudget;
import com.expense_management_service.mapper.CostCenterBudgetMapper;
import com.expense_management_service.repository.CostCenterBudgetRepository;
import com.expense_management_service.repository.CostCenterRepository;
import com.expense_management_service.service.CostCenterBudgetService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class CostCenterBudgetServiceImpl implements CostCenterBudgetService {

    private final CostCenterBudgetRepository costCenterBudgetRepository;
    private final CostCenterRepository costCenterRepository;
    private final CostCenterBudgetMapper costCenterBudgetMapper;

    @Override
    public CostCenterBudgetResponse create(CostCenterBudgetRequest request) {
        CostCenterBudget entity = costCenterBudgetMapper.toEntity(request);
        entity.setCostCenter(findCostCenter(request.costCenterId()));
        return costCenterBudgetMapper.toResponse(costCenterBudgetRepository.save(entity));
    }

    @Override
    public CostCenterBudgetResponse update(UUID budgetId, CostCenterBudgetRequest request) {
        CostCenterBudget entity = findEntity(budgetId);
        costCenterBudgetMapper.updateEntity(entity, request);
        entity.setCostCenter(findCostCenter(request.costCenterId()));
        return costCenterBudgetMapper.toResponse(costCenterBudgetRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public CostCenterBudgetResponse getById(UUID budgetId) {
        return costCenterBudgetMapper.toResponse(findEntity(budgetId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CostCenterBudgetResponse> getAll(Pageable pageable) {
        return costCenterBudgetRepository.findAll(pageable).map(costCenterBudgetMapper::toResponse);
    }

    @Override
    public void delete(UUID budgetId) {
        costCenterBudgetRepository.delete(findEntity(budgetId));
    }

    private CostCenter findCostCenter(UUID costCenterId) {
        return costCenterRepository.findById(costCenterId)
                .orElseThrow(() -> new ResourceNotFoundException("CostCenter not found with id: " + costCenterId));
    }

    private CostCenterBudget findEntity(UUID budgetId) {
        return costCenterBudgetRepository.findById(budgetId)
                .orElseThrow(() -> new ResourceNotFoundException("CostCenterBudget not found with id: " + budgetId));
    }
}
