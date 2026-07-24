package com.expense_management_service.service.impl;

import java.util.List;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.CostCenterRequest;
import com.expense_management_service.dto.response.CostCenterResponse;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.mapper.CostCenterMapper;
import com.expense_management_service.repository.CostCenterRepository;
import com.expense_management_service.service.CostCenterService;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class CostCenterServiceImpl implements CostCenterService {

    private final CostCenterRepository costCenterRepository;
    private final CostCenterMapper costCenterMapper;

    @Override
    public CostCenterResponse create(CostCenterRequest request) {
        CostCenter entity = costCenterMapper.toEntity(request);
        entity.setParentCostCenter(resolveParent(null, request.parentCostCenterId()));
        return costCenterMapper.toResponse(costCenterRepository.save(entity));
    }

    @Override
    public CostCenterResponse update(UUID costCenterId, CostCenterRequest request) {
        CostCenter entity = findEntity(costCenterId);
        costCenterMapper.updateEntity(entity, request);
        entity.setParentCostCenter(resolveParent(costCenterId, request.parentCostCenterId()));
        return costCenterMapper.toResponse(costCenterRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public CostCenterResponse getById(UUID costCenterId) {
        return costCenterMapper.toResponse(findEntity(costCenterId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CostCenterResponse> getAll() {
        return costCenterRepository.findAll().stream().map(costCenterMapper::toResponse).toList();
    }

    @Override
    public void delete(UUID costCenterId) {
        costCenterRepository.delete(findEntity(costCenterId));
    }

    private CostCenter resolveParent(UUID costCenterId, UUID parentCostCenterId) {
        if (parentCostCenterId == null) {
            return null;
        }
        if (parentCostCenterId.equals(costCenterId)) {
            throw new IllegalArgumentException("A cost center cannot be its own parent");
        }
        CostCenter parent = costCenterRepository.findById(parentCostCenterId)
                .orElseThrow(() -> new ResourceNotFoundException("CostCenter not found with id: " + parentCostCenterId));

        if (costCenterId != null) {
            for (CostCenter ancestor = parent; ancestor != null; ancestor = ancestor.getParentCostCenter()) {
                if (ancestor.getCostCenterId().equals(costCenterId)) {
                    throw new IllegalArgumentException("Assigning this parent would create a cost center hierarchy cycle");
                }
            }
        }
        return parent;
    }

    private CostCenter findEntity(UUID costCenterId) {
        return costCenterRepository.findById(costCenterId)
                .orElseThrow(() -> new ResourceNotFoundException("CostCenter not found with id: " + costCenterId));
    }
}
