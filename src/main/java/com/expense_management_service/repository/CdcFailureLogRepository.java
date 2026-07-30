package com.expense_management_service.repository;

import com.expense_management_service.entity.CdcFailureLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CdcFailureLogRepository extends JpaRepository<CdcFailureLog, UUID> {

    List<CdcFailureLog> findByStatusInAndRetryCountLessThan(List<String> statuses, Integer retryCount);
}
