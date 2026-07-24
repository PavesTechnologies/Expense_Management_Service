package com.expense_management_service.service.impl;

import java.util.List;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ApprovalDelegationRequest;
import com.expense_management_service.dto.response.ApprovalDelegationResponse;
import com.expense_management_service.entity.ApprovalDelegation;
import com.expense_management_service.mapper.ApprovalDelegationMapper;
import com.expense_management_service.repository.ApprovalDelegationRepository;
import com.expense_management_service.service.ApprovalDelegationService;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ApprovalDelegationServiceImpl implements ApprovalDelegationService {

    private final ApprovalDelegationRepository approvalDelegationRepository;
    private final ApprovalDelegationMapper approvalDelegationMapper;

    @Override
    public ApprovalDelegationResponse create(ApprovalDelegationRequest request) {
        ApprovalDelegation entity = approvalDelegationMapper.toEntity(request);
        return approvalDelegationMapper.toResponse(approvalDelegationRepository.save(entity));
    }

    @Override
    public ApprovalDelegationResponse update(UUID delegationId, ApprovalDelegationRequest request) {
        ApprovalDelegation entity = findEntity(delegationId);
        approvalDelegationMapper.updateEntity(entity, request);
        return approvalDelegationMapper.toResponse(approvalDelegationRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public ApprovalDelegationResponse getById(UUID delegationId) {
        return approvalDelegationMapper.toResponse(findEntity(delegationId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApprovalDelegationResponse> getAll() {
        return approvalDelegationRepository.findAll().stream().map(approvalDelegationMapper::toResponse).toList();
    }

    @Override
    public void delete(UUID delegationId) {
        approvalDelegationRepository.delete(findEntity(delegationId));
    }

    private ApprovalDelegation findEntity(UUID delegationId) {
        return approvalDelegationRepository.findById(delegationId)
                .orElseThrow(() -> new ResourceNotFoundException("ApprovalDelegation not found with id: " + delegationId));
    }
}
