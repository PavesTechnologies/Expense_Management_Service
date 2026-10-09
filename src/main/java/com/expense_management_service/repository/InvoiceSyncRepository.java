package com.expense_management_service.repository;

import com.expense_management_service.entity.InvoiceSync;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InvoiceSyncRepository extends JpaRepository<InvoiceSync, UUID> {

    /** Duplicate-handoff guard for {@code InvoiceHandoffServiceImpl.markHandedOff}. */
    boolean existsByLineItem_LineItemIdAndSyncStatus(UUID lineItemId, String syncStatus);

    List<InvoiceSync> findByLineItem_LineItemIdOrderBySyncDateDesc(UUID lineItemId);

    /** The Client Billing page's "Handed off" tab — newest first. */
    Page<InvoiceSync> findBySyncStatusOrderBySyncDateDesc(String syncStatus, Pageable pageable);

    long countBySyncStatus(String syncStatus);
}
