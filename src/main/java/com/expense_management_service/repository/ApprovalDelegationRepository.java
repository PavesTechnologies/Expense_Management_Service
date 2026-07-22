package com.expense_management_service.repository;

import com.expense_management_service.entity.ApprovalDelegation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ApprovalDelegationRepository extends JpaRepository<ApprovalDelegation, UUID> {
}
