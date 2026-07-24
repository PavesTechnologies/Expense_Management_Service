package com.expense_management_service.service.impl;

import java.util.List;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.CostAllocationRequest;
import com.expense_management_service.dto.response.CostAllocationResponse;
import com.expense_management_service.entity.CostAllocation;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.mapper.CostAllocationMapper;
import com.expense_management_service.repository.CostAllocationRepository;
import com.expense_management_service.repository.CostCenterRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.service.CostAllocationService;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class CostAllocationServiceImpl implements CostAllocationService {

    private final CostAllocationRepository costAllocationRepository;
    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final CostCenterRepository costCenterRepository;
    private final CostAllocationMapper costAllocationMapper;

    @Override
    public CostAllocationResponse create(CostAllocationRequest request) {
        CostAllocation entity = costAllocationMapper.toEntity(request);
        entity.setLineItem(findLineItem(request.lineItemId()));
        entity.setCostCenter(findCostCenter(request.costCenterId()));
        return costAllocationMapper.toResponse(costAllocationRepository.save(entity));
    }

    @Override
    public CostAllocationResponse update(UUID allocationId, CostAllocationRequest request) {
        CostAllocation entity = findEntity(allocationId);
        costAllocationMapper.updateEntity(entity, request);
        entity.setLineItem(findLineItem(request.lineItemId()));
        entity.setCostCenter(findCostCenter(request.costCenterId()));
        return costAllocationMapper.toResponse(costAllocationRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public CostAllocationResponse getById(UUID allocationId) {
        return costAllocationMapper.toResponse(findEntity(allocationId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CostAllocationResponse> getAll() {
        return costAllocationRepository.findAll().stream().map(costAllocationMapper::toResponse).toList();
    }

    @Override
    public void delete(UUID allocationId) {
        costAllocationRepository.delete(findEntity(allocationId));
    }

    private ExpenseLineItem findLineItem(UUID lineItemId) {
        return expenseLineItemRepository.findById(lineItemId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseLineItem not found with id: " + lineItemId));
    }

    private CostCenter findCostCenter(UUID costCenterId) {
        return costCenterRepository.findById(costCenterId)
                .orElseThrow(() -> new ResourceNotFoundException("CostCenter not found with id: " + costCenterId));
    }

    private CostAllocation findEntity(UUID allocationId) {
        return costAllocationRepository.findById(allocationId)
                .orElseThrow(() -> new ResourceNotFoundException("CostAllocation not found with id: " + allocationId));
    }
}
