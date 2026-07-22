package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.IntegrationSyncRequest;
import com.expense_management_service.dto.response.IntegrationSyncResponse;
import com.expense_management_service.entity.IntegrationSync;
import org.springframework.stereotype.Component;

@Component
public class IntegrationSyncMapper {

    public IntegrationSync toEntity(IntegrationSyncRequest request) {
        return IntegrationSync.builder()
                .integrationName(request.integrationName())
                .referenceId(request.referenceId())
                .requestPayload(request.requestPayload())
                .responsePayload(request.responsePayload())
                .syncStatus(request.syncStatus())
                .retryCount(request.retryCount())
                .build();
    }

    public void updateEntity(IntegrationSync entity, IntegrationSyncRequest request) {
        entity.setIntegrationName(request.integrationName());
        entity.setReferenceId(request.referenceId());
        entity.setRequestPayload(request.requestPayload());
        entity.setResponsePayload(request.responsePayload());
        entity.setSyncStatus(request.syncStatus());
        entity.setRetryCount(request.retryCount());
    }

    public IntegrationSyncResponse toResponse(IntegrationSync entity) {
        return new IntegrationSyncResponse(
                entity.getIntegrationId(),
                entity.getIntegrationName(),
                entity.getReferenceId(),
                entity.getRequestPayload(),
                entity.getResponsePayload(),
                entity.getSyncStatus(),
                entity.getRetryCount(),
                entity.getLastSyncedAt()
        );
    }
}
