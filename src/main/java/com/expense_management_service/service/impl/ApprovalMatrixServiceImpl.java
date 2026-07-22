package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ApprovalMatrixRequest;
import com.expense_management_service.dto.response.ApprovalMatrixResponse;
import com.expense_management_service.entity.ApprovalMatrix;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.mapper.ApprovalMatrixMapper;
import com.expense_management_service.repository.ApprovalMatrixRepository;
import com.expense_management_service.repository.CostCenterRepository;
import com.expense_management_service.service.ApprovalMatrixService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ApprovalMatrixServiceImpl implements ApprovalMatrixService {

    private final ApprovalMatrixRepository approvalMatrixRepository;
    private final CostCenterRepository costCenterRepository;
    private final ApprovalMatrixMapper approvalMatrixMapper;

    @Override
    public ApprovalMatrixResponse create(ApprovalMatrixRequest request) {
        ApprovalMatrix entity = approvalMatrixMapper.toEntity(request);
        entity.setCostCenter(findCostCenter(request.costCenterId()));
        return approvalMatrixMapper.toResponse(approvalMatrixRepository.save(entity));
    }

    @Override
    public ApprovalMatrixResponse update(UUID matrixId, ApprovalMatrixRequest request) {
        ApprovalMatrix entity = findEntity(matrixId);
        approvalMatrixMapper.updateEntity(entity, request);
        entity.setCostCenter(findCostCenter(request.costCenterId()));
        return approvalMatrixMapper.toResponse(approvalMatrixRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public ApprovalMatrixResponse getById(UUID matrixId) {
        return approvalMatrixMapper.toResponse(findEntity(matrixId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ApprovalMatrixResponse> getAll(Pageable pageable) {
        return approvalMatrixRepository.findAll(pageable).map(approvalMatrixMapper::toResponse);
    }

    @Override
    public void delete(UUID matrixId) {
        approvalMatrixRepository.delete(findEntity(matrixId));
    }

    private CostCenter findCostCenter(UUID costCenterId) {
        return costCenterRepository.findById(costCenterId)
                .orElseThrow(() -> new ResourceNotFoundException("CostCenter not found with id: " + costCenterId));
    }

    private ApprovalMatrix findEntity(UUID matrixId) {
        return approvalMatrixRepository.findById(matrixId)
                .orElseThrow(() -> new ResourceNotFoundException("ApprovalMatrix not found with id: " + matrixId));
    }
}
