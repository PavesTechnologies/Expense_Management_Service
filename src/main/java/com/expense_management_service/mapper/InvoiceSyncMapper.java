package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.InvoiceSyncRequest;
import com.expense_management_service.dto.response.InvoiceSyncResponse;
import com.expense_management_service.entity.InvoiceSync;
import org.springframework.stereotype.Component;

@Component
public class InvoiceSyncMapper {

    public InvoiceSync toEntity(InvoiceSyncRequest request) {
        return InvoiceSync.builder()
                .invoiceReference(request.invoiceReference())
                .syncStatus(request.syncStatus())
                .retryCount(request.retryCount())
                .remarks(request.remarks())
                .build();
    }

    public void updateEntity(InvoiceSync entity, InvoiceSyncRequest request) {
        entity.setInvoiceReference(request.invoiceReference());
        entity.setSyncStatus(request.syncStatus());
        entity.setRetryCount(request.retryCount());
        entity.setRemarks(request.remarks());
    }

    public InvoiceSyncResponse toResponse(InvoiceSync entity) {
        return new InvoiceSyncResponse(
                entity.getSyncId(),
                entity.getLineItem() != null ? entity.getLineItem().getLineItemId() : null,
                entity.getInvoiceReference(),
                entity.getSyncStatus(),
                entity.getSyncDate(),
                entity.getRetryCount(),
                entity.getRemarks()
        );
    }
}
