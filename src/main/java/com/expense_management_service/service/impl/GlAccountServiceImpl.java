package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.GlAccountRequest;
import com.expense_management_service.dto.response.GlAccountResponse;
import com.expense_management_service.entity.GlAccount;
import com.expense_management_service.mapper.GlAccountMapper;
import com.expense_management_service.repository.GlAccountRepository;
import com.expense_management_service.service.GlAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class GlAccountServiceImpl implements GlAccountService {

    private final GlAccountRepository glAccountRepository;
    private final GlAccountMapper glAccountMapper;

    @Override
    public GlAccountResponse create(GlAccountRequest request) {
        GlAccount entity = glAccountMapper.toEntity(request);
        return glAccountMapper.toResponse(glAccountRepository.save(entity));
    }

    @Override
    public GlAccountResponse update(UUID glAccountId, GlAccountRequest request) {
        GlAccount entity = findEntity(glAccountId);
        glAccountMapper.updateEntity(entity, request);
        return glAccountMapper.toResponse(glAccountRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public GlAccountResponse getById(UUID glAccountId) {
        return glAccountMapper.toResponse(findEntity(glAccountId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<GlAccountResponse> getAll(Pageable pageable) {
        return glAccountRepository.findAll(pageable).map(glAccountMapper::toResponse);
    }

    @Override
    public void delete(UUID glAccountId) {
        glAccountRepository.delete(findEntity(glAccountId));
    }

    private GlAccount findEntity(UUID glAccountId) {
        return glAccountRepository.findById(glAccountId)
                .orElseThrow(() -> new ResourceNotFoundException("GlAccount not found with id: " + glAccountId));
    }
}
