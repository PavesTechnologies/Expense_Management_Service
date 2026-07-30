package com.expense_management_service.repository;

import com.expense_management_service.entity.SystemConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SystemConfigurationRepository extends JpaRepository<SystemConfiguration, UUID> {

    /** config_key carries a table-level unique constraint - see entity. */
    Optional<SystemConfiguration> findByConfigKey(String configKey);
}
