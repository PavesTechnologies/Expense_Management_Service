package com.expense_management_service.service;

import com.expense_management_service.dto.request.InvoiceSyncRequest;
import com.expense_management_service.dto.response.InvoiceSyncResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface InvoiceSyncService {

    InvoiceSyncResponse create(InvoiceSyncRequest request);

    InvoiceSyncResponse update(UUID syncId, InvoiceSyncRequest request);

    InvoiceSyncResponse getById(UUID syncId);

    Page<InvoiceSyncResponse> getAll(Pageable pageable);

    void delete(UUID syncId);
}
