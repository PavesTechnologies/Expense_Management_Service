package com.expense_management_service.service;

import java.util.List;

import com.expense_management_service.dto.request.IntegrationSyncRequest;
import com.expense_management_service.dto.response.IntegrationSyncResponse;


import java.util.UUID;

public interface IntegrationSyncService {

    IntegrationSyncResponse create(IntegrationSyncRequest request);

    IntegrationSyncResponse update(UUID integrationId, IntegrationSyncRequest request);

    IntegrationSyncResponse getById(UUID integrationId);

    List<IntegrationSyncResponse> getAll();

    void delete(UUID integrationId);
}
