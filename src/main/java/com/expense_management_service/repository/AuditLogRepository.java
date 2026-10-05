package com.expense_management_service.repository;

import com.expense_management_service.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /** History of one record, newest first. */
    List<AuditLog> findTop200ByEntityNameAndEntityIdOrderByPerformedAtDesc(String entityName, UUID entityId);
}
