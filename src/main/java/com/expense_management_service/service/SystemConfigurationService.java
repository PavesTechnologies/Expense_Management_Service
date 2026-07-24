package com.expense_management_service.service;

import com.expense_management_service.dto.request.SystemConfigurationRequest;
import com.expense_management_service.dto.response.SystemConfigurationResponse;
import java.util.List;
import java.util.UUID;

public interface SystemConfigurationService {

    SystemConfigurationResponse create(SystemConfigurationRequest request);

    SystemConfigurationResponse update(UUID configId, SystemConfigurationRequest request);

    SystemConfigurationResponse getById(UUID configId);

    List<SystemConfigurationResponse> getAll();

    void delete(UUID configId);
}
