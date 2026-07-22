package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.SystemConfigurationRequest;
import com.expense_management_service.dto.response.SystemConfigurationResponse;
import com.expense_management_service.entity.SystemConfiguration;
import com.expense_management_service.mapper.SystemConfigurationMapper;
import com.expense_management_service.repository.SystemConfigurationRepository;
import com.expense_management_service.service.SystemConfigurationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class SystemConfigurationServiceImpl implements SystemConfigurationService {

    private final SystemConfigurationRepository systemConfigurationRepository;
    private final SystemConfigurationMapper systemConfigurationMapper;

    @Override
    public SystemConfigurationResponse create(SystemConfigurationRequest request) {
        SystemConfiguration entity = systemConfigurationMapper.toEntity(request);
        return systemConfigurationMapper.toResponse(systemConfigurationRepository.save(entity));
    }

    @Override
    public SystemConfigurationResponse update(UUID configId, SystemConfigurationRequest request) {
        SystemConfiguration entity = findEntity(configId);
        systemConfigurationMapper.updateEntity(entity, request);
        return systemConfigurationMapper.toResponse(systemConfigurationRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public SystemConfigurationResponse getById(UUID configId) {
        return systemConfigurationMapper.toResponse(findEntity(configId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SystemConfigurationResponse> getAll(Pageable pageable) {
        return systemConfigurationRepository.findAll(pageable).map(systemConfigurationMapper::toResponse);
    }

    @Override
    public void delete(UUID configId) {
        systemConfigurationRepository.delete(findEntity(configId));
    }

    private SystemConfiguration findEntity(UUID configId) {
        return systemConfigurationRepository.findById(configId)
                .orElseThrow(() -> new ResourceNotFoundException("SystemConfiguration not found with id: " + configId));
    }
}
