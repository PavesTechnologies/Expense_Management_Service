package com.expense_management_service.repository;

import com.expense_management_service.entity.VerificationQuery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VerificationQueryRepository extends JpaRepository<VerificationQuery, UUID> {
}
