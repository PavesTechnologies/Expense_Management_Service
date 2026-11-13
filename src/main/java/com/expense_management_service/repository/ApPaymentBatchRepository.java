package com.expense_management_service.repository;

import com.expense_management_service.entity.ApPaymentBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ApPaymentBatchRepository extends JpaRepository<ApPaymentBatch, UUID> {

    boolean existsByBankReference(String bankReference);
}
