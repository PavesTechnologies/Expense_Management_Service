package com.expense_management_service.service;

import com.expense_management_service.dto.request.IntegrationSyncRequest;
import com.expense_management_service.dto.response.IntegrationSyncResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface IntegrationSyncService {

    IntegrationSyncResponse create(IntegrationSyncRequest request);

    IntegrationSyncResponse update(UUID integrationId, IntegrationSyncRequest request);

    IntegrationSyncResponse getById(UUID integrationId);

    Page<IntegrationSyncResponse> getAll(Pageable pageable);

    void delete(UUID integrationId);
}
