package com.expense_management_service.service;

import java.util.List;

import com.expense_management_service.dto.request.InvoiceSyncRequest;
import com.expense_management_service.dto.response.InvoiceSyncResponse;


import java.util.UUID;

public interface InvoiceSyncService {

    InvoiceSyncResponse create(InvoiceSyncRequest request);

    InvoiceSyncResponse update(UUID syncId, InvoiceSyncRequest request);

    InvoiceSyncResponse getById(UUID syncId);

    List<InvoiceSyncResponse> getAll();

    void delete(UUID syncId);
}
