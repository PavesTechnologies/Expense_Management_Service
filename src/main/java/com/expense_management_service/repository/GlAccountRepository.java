package com.expense_management_service.repository;

import com.expense_management_service.entity.GlAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface GlAccountRepository extends JpaRepository<GlAccount, UUID> {
}
