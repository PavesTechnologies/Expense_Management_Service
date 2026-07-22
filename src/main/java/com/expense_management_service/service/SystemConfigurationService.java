package com.expense_management_service.service;

import com.expense_management_service.dto.request.SystemConfigurationRequest;
import com.expense_management_service.dto.response.SystemConfigurationResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface SystemConfigurationService {

    SystemConfigurationResponse create(SystemConfigurationRequest request);

    SystemConfigurationResponse update(UUID configId, SystemConfigurationRequest request);

    SystemConfigurationResponse getById(UUID configId);

    Page<SystemConfigurationResponse> getAll(Pageable pageable);

    void delete(UUID configId);
}
