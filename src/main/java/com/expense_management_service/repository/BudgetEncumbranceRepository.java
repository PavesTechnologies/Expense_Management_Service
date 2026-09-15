package com.expense_management_service.repository;

import com.expense_management_service.entity.BudgetEncumbrance;
import com.expense_management_service.enums.BudgetEncumbranceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface BudgetEncumbranceRepository extends JpaRepository<BudgetEncumbrance, UUID> {

    List<BudgetEncumbrance> findByReport_ReportIdAndSubmissionCycleAndStatus(UUID reportId, Integer submissionCycle, BudgetEncumbranceStatus status);

    List<BudgetEncumbrance> findByBudget_BudgetIdAndStatus(UUID budgetId, BudgetEncumbranceStatus status);

    /** The figure Effective Available Budget subtracts, and the same figure a rollover's "true unencumbered remainder" check needs for its source budget. */
    @Query("select coalesce(sum(e.amount), 0) from BudgetEncumbrance e where e.budget.budgetId = :budgetId and e.status = :status")
    BigDecimal sumAmountByBudget_BudgetIdAndStatus(@Param("budgetId") UUID budgetId, @Param("status") BudgetEncumbranceStatus status);

    /**
     * Same rows {@link #sumAmountByBudget_BudgetIdAndStatus} sums, but as a locking (FOR SHARE) read -
     * used only by {@code effectiveAvailable} from inside {@code validateAndEncumber}.
     * <p>
     * <b>Production-readiness audit, Bug 7 (real-DB concurrency testing):</b> a real MySQL run of the
     * concurrency test exposed that under InnoDB's default REPEATABLE READ isolation, the plain
     * aggregate query above can still return a STALE sum - one that omits another transaction's just-
     * committed encumbrance - even though the caller already waited on and acquired {@code
     * CostCenterBudgetRepository}'s {@code PESSIMISTIC_WRITE} lock on the sibling {@code
     * CostCenterBudget} row first. A lock on one table does not refresh a consistent-read snapshot
     * already established (at this transaction's first plain SELECT) against a DIFFERENT table - two
     * concurrent submissions against the same Cost Center could each read "nothing yet encumbered" and
     * both pass validation, together overspending the budget. {@code LockModeType.PESSIMISTIC_READ}
     * forces InnoDB to serve the latest COMMITTED rows (a MySQL locking read always bypasses the
     * snapshot), closing the gap - confirmed against the real database, not just reasoned about.
     * <p>
     * Deliberately a separate, entity-returning query rather than adding {@code @Lock} to the
     * aggregate query above: a real run showed Hibernate silently drops a lock mode requested on a
     * bare {@code select sum(...)} projection (no "for update"/"for share" is ever added to the
     * generated SQL, and no exception is thrown either) - {@code @Lock} is only reliably honored on a
     * query that returns managed entities.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select e from BudgetEncumbrance e where e.budget.budgetId = :budgetId and e.status = :status")
    List<BudgetEncumbrance> findForUpdateByBudget_BudgetIdAndStatus(@Param("budgetId") UUID budgetId, @Param("status") BudgetEncumbranceStatus status);
}
