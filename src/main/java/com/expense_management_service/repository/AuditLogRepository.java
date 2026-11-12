package com.expense_management_service.repository;

import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.enums.AuditSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /** History of one record, newest first. */
    List<AuditLog> findTop200ByEntityNameAndEntityIdOrderByPerformedAtDesc(String entityName, UUID entityId);

    /**
     * The audit trail, newest first, with every filter optional ({@code q} is already lower-cased
     * and wrapped in %; {@code to} is exclusive).
     */
    @Query("""
            select a from AuditLog a where
                (:entityName is null or a.entityName = :entityName)
            and (:entityId is null or a.entityId = :entityId)
            and (:action is null or a.action = :action)
            and (:performedBy is null or a.performedBy = :performedBy)
            and (:source is null or a.source = :source)
            and (:from is null or a.performedAt >= :from)
            and (:to is null or a.performedAt < :to)
            and (:q is null or lower(a.action) like :q or lower(a.entityName) like :q
                 or lower(a.performedBy) like :q or lower(a.reason) like :q)
            order by a.performedAt desc
            """)
    Page<AuditLog> search(@Param("entityName") String entityName,
                          @Param("entityId") UUID entityId,
                          @Param("action") String action,
                          @Param("performedBy") String performedBy,
                          @Param("source") AuditSource source,
                          @Param("from") LocalDateTime from,
                          @Param("to") LocalDateTime to,
                          @Param("q") String q,
                          Pageable pageable);

    @Query("select distinct a.entityName from AuditLog a order by a.entityName")
    List<String> findDistinctEntityNames();

    @Query("select distinct a.action from AuditLog a order by a.action")
    List<String> findDistinctActions();
}
