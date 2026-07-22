package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.SystemConfigurationRequest;
import com.expense_management_service.dto.response.SystemConfigurationResponse;
import com.expense_management_service.entity.SystemConfiguration;
import org.springframework.stereotype.Component;

@Component
public class SystemConfigurationMapper {

    public SystemConfiguration toEntity(SystemConfigurationRequest request) {
        return SystemConfiguration.builder()
                .configKey(request.configKey())
                .configValue(request.configValue())
                .dataType(request.dataType())
                .description(request.description())
                .build();
    }

    public void updateEntity(SystemConfiguration entity, SystemConfigurationRequest request) {
        entity.setConfigKey(request.configKey());
        entity.setConfigValue(request.configValue());
        entity.setDataType(request.dataType());
        entity.setDescription(request.description());
    }

    public SystemConfigurationResponse toResponse(SystemConfiguration entity) {
        return new SystemConfigurationResponse(
                entity.getConfigId(),
                entity.getConfigKey(),
                entity.getConfigValue(),
                entity.getDataType(),
                entity.getDescription(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
