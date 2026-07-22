package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.IntegrationSyncRequest;
import com.expense_management_service.dto.response.IntegrationSyncResponse;
import com.expense_management_service.entity.IntegrationSync;
import com.expense_management_service.mapper.IntegrationSyncMapper;
import com.expense_management_service.repository.IntegrationSyncRepository;
import com.expense_management_service.service.IntegrationSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class IntegrationSyncServiceImpl implements IntegrationSyncService {

    private final IntegrationSyncRepository integrationSyncRepository;
    private final IntegrationSyncMapper integrationSyncMapper;

    @Override
    public IntegrationSyncResponse create(IntegrationSyncRequest request) {
        IntegrationSync entity = integrationSyncMapper.toEntity(request);
        entity.setLastSyncedAt(LocalDateTime.now());
        return integrationSyncMapper.toResponse(integrationSyncRepository.save(entity));
    }

    @Override
    public IntegrationSyncResponse update(UUID integrationId, IntegrationSyncRequest request) {
        IntegrationSync entity = findEntity(integrationId);
        integrationSyncMapper.updateEntity(entity, request);
        entity.setLastSyncedAt(LocalDateTime.now());
        return integrationSyncMapper.toResponse(integrationSyncRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public IntegrationSyncResponse getById(UUID integrationId) {
        return integrationSyncMapper.toResponse(findEntity(integrationId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<IntegrationSyncResponse> getAll(Pageable pageable) {
        return integrationSyncRepository.findAll(pageable).map(integrationSyncMapper::toResponse);
    }

    @Override
    public void delete(UUID integrationId) {
        integrationSyncRepository.delete(findEntity(integrationId));
    }

    private IntegrationSync findEntity(UUID integrationId) {
        return integrationSyncRepository.findById(integrationId)
                .orElseThrow(() -> new ResourceNotFoundException("IntegrationSync not found with id: " + integrationId));
    }
}
