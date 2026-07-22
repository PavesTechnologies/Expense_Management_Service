package com.expense_management_service.repository;

import com.expense_management_service.entity.ReceiptOcr;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReceiptOcrRepository extends JpaRepository<ReceiptOcr, UUID> {
}
