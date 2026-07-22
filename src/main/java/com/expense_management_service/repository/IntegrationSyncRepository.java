package com.expense_management_service.repository;

import com.expense_management_service.entity.IntegrationSync;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IntegrationSyncRepository extends JpaRepository<IntegrationSync, UUID> {
}
