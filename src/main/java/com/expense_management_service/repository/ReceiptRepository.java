package com.expense_management_service.repository;

import com.expense_management_service.entity.Receipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReceiptRepository extends JpaRepository<Receipt, UUID> {

    /** Every receipt on a report — including ones not yet linked to a line item. */
    List<Receipt> findByReport_ReportId(UUID reportId);

    /** Retained for the (still-supported) line-item-scoped listing endpoint. */
    List<Receipt> findByLineItem_LineItemId(UUID lineItemId);
}
