package com.expense_management_service.repository;

import com.expense_management_service.entity.GlAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GlAccountRepository extends JpaRepository<GlAccount, UUID> {

    Optional<GlAccount> findByGlAccountCodeIgnoreCase(String glAccountCode);

    List<GlAccount> findByStatusIgnoreCaseOrderByGlAccountNameAsc(String status);
}
