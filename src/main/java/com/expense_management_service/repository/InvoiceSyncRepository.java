package com.expense_management_service.repository;

import com.expense_management_service.entity.InvoiceSync;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface InvoiceSyncRepository extends JpaRepository<InvoiceSync, UUID> {

    /** Duplicate-handoff guard for {@code InvoiceHandoffServiceImpl.markHandedOff}. */
    boolean existsByLineItem_LineItemIdAndSyncStatus(UUID lineItemId, String syncStatus);

    List<InvoiceSync> findByLineItem_LineItemIdOrderBySyncDateDesc(UUID lineItemId);

    /**
     * The Client Billing page's "Handed off" tab and AR's handed-off pull — newest first. All
     * filters are optional (pass {@code null} to skip); {@code handedOffFrom} is inclusive and
     * {@code handedOffBefore} exclusive. LEFT JOIN on project so project-less lines still appear
     * when no project filter is given.
     */
    @Query("""
            SELECT s FROM InvoiceSync s JOIN s.lineItem l LEFT JOIN l.project p
            WHERE s.syncStatus = :syncStatus
              AND (:clientId IS NULL OR l.resolvedClientId = :clientId)
              AND (:pmsProjectId IS NULL OR p.pmsProjectId = :pmsProjectId)
              AND (:projectCode IS NULL OR p.projectCode = :projectCode)
              AND (:handedOffFrom IS NULL OR s.syncDate >= :handedOffFrom)
              AND (:handedOffBefore IS NULL OR s.syncDate < :handedOffBefore)
            ORDER BY s.syncDate DESC
            """)
    Page<InvoiceSync> findHandedOff(
            @Param("syncStatus") String syncStatus,
            @Param("clientId") UUID clientId,
            @Param("pmsProjectId") Long pmsProjectId,
            @Param("projectCode") String projectCode,
            @Param("handedOffFrom") LocalDateTime handedOffFrom,
            @Param("handedOffBefore") LocalDateTime handedOffBefore,
            Pageable pageable);

    long countBySyncStatus(String syncStatus);
}
