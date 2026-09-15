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

    public CostCenterBudgetResponse toResponse(CostCenterBudget entity) {
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
                entity.getUpdatedAt()
        );
    }
}
