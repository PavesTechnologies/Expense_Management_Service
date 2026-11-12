package com.expense_management_service.service.impl;

import java.util.List;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.SystemConfigurationRequest;
import com.expense_management_service.dto.response.SystemConfigurationResponse;
import com.expense_management_service.entity.SystemConfiguration;
import com.expense_management_service.mapper.SystemConfigurationMapper;
import com.expense_management_service.repository.SystemConfigurationRepository;
import com.expense_management_service.service.SystemConfigurationService;
import lombok.RequiredArgsConstructor;

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
        assertKeyNotDuplicate(request.configKey(), null);
        SystemConfiguration entity = systemConfigurationMapper.toEntity(request);
        return systemConfigurationMapper.toResponse(systemConfigurationRepository.save(entity));
    }

    @Override
    public SystemConfigurationResponse update(UUID configId, SystemConfigurationRequest request) {
        SystemConfiguration entity = findEntity(configId);
        assertKeyNotDuplicate(request.configKey(), configId);
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
    public List<SystemConfigurationResponse> getAll() {
        return systemConfigurationRepository.findAll().stream().map(systemConfigurationMapper::toResponse).toList();
    }

    @Override
    public void delete(UUID configId) {
        systemConfigurationRepository.delete(findEntity(configId));
    }

    /** config_key is unique in the table; checked here so a duplicate is a clear 409, not a constraint error. */
    private void assertKeyNotDuplicate(String configKey, UUID currentConfigId) {
        systemConfigurationRepository.findByConfigKey(configKey)
                .filter(existing -> !existing.getConfigId().equals(currentConfigId))
                .ifPresent(existing -> {
                    throw new DuplicateResourceException("A setting with key '" + configKey + "' already exists");
                });
    }

    private SystemConfiguration findEntity(UUID configId) {
        return systemConfigurationRepository.findById(configId)
                .orElseThrow(() -> new ResourceNotFoundException("SystemConfiguration not found with id: " + configId));
    }
}
