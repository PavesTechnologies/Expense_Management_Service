package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.CostCenterBudgetRequest;
import com.expense_management_service.dto.response.CostCenterBudgetResponse;
import com.expense_management_service.entity.CostCenterBudget;
import org.springframework.stereotype.Component;

@Component
public class CostCenterBudgetMapper {

    /**
     * {@code availableBudget} and {@code rolloverFromPrevious} are deliberately not mapped here -
     * both need cross-field validation (against {@code budgetAmount}, and against the rollover
     * source budget's true unencumbered remainder / cap, respectively) that only the service can
     * perform, exactly mirroring how {@code availableBudget} was already excluded before Phase 3.
     */
    public CostCenterBudget toEntity(CostCenterBudgetRequest request) {
        return CostCenterBudget.builder()
                .fiscalYear(request.fiscalYear())
                .budgetAmount(request.budgetAmount())
                .allowRollover(request.allowRollover() != null ? request.allowRollover() : false)
                .rolloverCap(request.rolloverCap())
                .warningThreshold(request.warningThreshold())
                .build();
    }

    public void updateEntity(CostCenterBudget entity, CostCenterBudgetRequest request) {
        entity.setFiscalYear(request.fiscalYear());
        entity.setBudgetAmount(request.budgetAmount());
        entity.setAllowRollover(request.allowRollover() != null ? request.allowRollover() : false);
        entity.setRolloverCap(request.rolloverCap());
        entity.setWarningThreshold(request.warningThreshold());
    }

    /**
     * Money actually spent on expenses: the pool less what is still available, less the advances and
     * fees already deducted from that same available figure. Floored at zero.
     */
    static java.math.BigDecimal actualUtilized(CostCenterBudget entity,
                                               java.math.BigDecimal cashAdvance,
                                               java.math.BigDecimal fee) {
        java.math.BigDecimal pool = nz(entity.getBudgetAmount()).add(nz(entity.getRolloverFromPrevious()));
        java.math.BigDecimal utilized = pool.subtract(nz(entity.getAvailableBudget()))
                .subtract(cashAdvance)
                .subtract(fee);
        return utilized.signum() < 0 ? java.math.BigDecimal.ZERO : utilized;
    }

    private static java.math.BigDecimal nz(java.math.BigDecimal value) {
        return value != null ? value : java.math.BigDecimal.ZERO;
    }

    public CostCenterBudgetResponse toResponse(CostCenterBudget entity) {
        return toResponse(entity, null);
    }

    /** {@code reserved} = this budget's ACTIVE encumbrances; null leaves reserved/effective available unset. */
    public CostCenterBudgetResponse toResponse(CostCenterBudget entity, java.math.BigDecimal reserved) {
        java.math.BigDecimal effective = reserved != null && entity.getAvailableBudget() != null
                ? entity.getAvailableBudget().subtract(reserved) : null;
        java.math.BigDecimal cashAdvance = entity.getCashAdvanceAllocated() != null
                ? entity.getCashAdvanceAllocated() : java.math.BigDecimal.ZERO;
        java.math.BigDecimal fee = entity.getCashAdvanceFeeAllocated() != null
                ? entity.getCashAdvanceFeeAllocated() : java.math.BigDecimal.ZERO;

        return new CostCenterBudgetResponse(
                entity.getBudgetId(),
                entity.getCostCenter() != null ? entity.getCostCenter().getCostCenterId() : null,
                entity.getCostCenter() != null ? entity.getCostCenter().getCostCenterName() : null,
                entity.getFiscalYear(),
                entity.getBudgetAmount(),
                entity.getAvailableBudget(),
                entity.getRolloverFromPrevious(),
                entity.getAllowRollover(),
                entity.getRolloverCap(),
                entity.getWarningThreshold(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                reserved,
                effective,
                cashAdvance,
                fee,
                actualUtilized(entity, cashAdvance, fee)
        );
    }
}
