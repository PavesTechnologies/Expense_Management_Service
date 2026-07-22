package com.expense_management_service.repository;

import com.expense_management_service.entity.InvoiceSync;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface InvoiceSyncRepository extends JpaRepository<InvoiceSync, UUID> {
}
