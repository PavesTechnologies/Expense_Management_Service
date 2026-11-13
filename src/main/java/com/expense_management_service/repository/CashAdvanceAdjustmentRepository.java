package com.expense_management_service.repository;

import com.expense_management_service.entity.CashAdvanceAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CashAdvanceAdjustmentRepository extends JpaRepository<CashAdvanceAdjustment, UUID> {

    /** Advance id of an adjustment, read without loading the adjustment into the persistence context. */
    @org.springframework.data.jpa.repository.Query("select a.cashAdvance.advanceId from CashAdvanceAdjustment a where a.adjustmentId = :id")
    java.util.Optional<UUID> findAdvanceIdOf(@org.springframework.data.repository.query.Param("id") UUID id);
    List<CashAdvanceAdjustment> findByCashAdvance_AdvanceId(UUID advanceId);

    /** Batch load for many advances at once (one query, not one per advance). */
    List<CashAdvanceAdjustment> findByCashAdvance_AdvanceIdIn(java.util.Collection<UUID> advanceIds);
    List<CashAdvanceAdjustment> findByReport_ReportId(UUID reportId);

    /** The adjustment created by an earlier call with the same idempotency key, if any. */
    java.util.Optional<CashAdvanceAdjustment> findByIdempotencyKey(String idempotencyKey);
}

