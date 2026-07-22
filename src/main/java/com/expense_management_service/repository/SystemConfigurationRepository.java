package com.expense_management_service.repository;

import com.expense_management_service.entity.SystemConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SystemConfigurationRepository extends JpaRepository<SystemConfiguration, UUID> {
}
