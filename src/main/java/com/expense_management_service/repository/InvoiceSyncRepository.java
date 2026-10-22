package com.expense_management_service.repository;

import com.expense_management_service.entity.InvoiceSync;
import com.expense_management_service.enums.ArDeliveryStatus;
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

    long countBySyncStatusAndArDeliveryStatus(String syncStatus, ArDeliveryStatus arDeliveryStatus);

    /**
     * Handoffs the AR delivery retry job should send now, oldest first: every FAILED one still under the attempt
     * limit, and any PENDING one recorded before {@code pendingRecordedBefore} - its send right after the handoff
     * never ran (e.g. the service stopped between the commit and the send).
     */
    @Query("""
            SELECT s.syncId FROM InvoiceSync s
            WHERE s.syncStatus = :syncStatus
              AND ((s.arDeliveryStatus = :failed AND COALESCE(s.arDeliveryAttempts, 0) < :maxAttempts)
                   OR (s.arDeliveryStatus = :pending AND s.syncDate < :pendingRecordedBefore))
            ORDER BY s.syncDate ASC
            """)
    List<UUID> findDueForArDelivery(
            @Param("syncStatus") String syncStatus,
            @Param("failed") ArDeliveryStatus failed,
            @Param("pending") ArDeliveryStatus pending,
            @Param("maxAttempts") int maxAttempts,
            @Param("pendingRecordedBefore") LocalDateTime pendingRecordedBefore);
}
