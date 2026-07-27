package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.CostCenterBudgetRequest;
import com.expense_management_service.dto.response.CostCenterBudgetResponse;
import com.expense_management_service.entity.CostCenterBudget;
import org.springframework.stereotype.Component;

@Component
public class CostCenterBudgetMapper {

    /** {@code availableBudget} is deliberately not mapped here — the service owns its default-fill and cross-field validation against {@code budgetAmount}. */
    public CostCenterBudget toEntity(CostCenterBudgetRequest request) {
        return CostCenterBudget.builder()
                .fiscalYear(request.fiscalYear())
                .budgetAmount(request.budgetAmount())
                .build();
    }

    public void updateEntity(CostCenterBudget entity, CostCenterBudgetRequest request) {
        entity.setFiscalYear(request.fiscalYear());
        entity.setBudgetAmount(request.budgetAmount());
    }

    public CostCenterBudgetResponse toResponse(CostCenterBudget entity) {
        return new CostCenterBudgetResponse(
                entity.getBudgetId(),
                entity.getCostCenter() != null ? entity.getCostCenter().getCostCenterId() : null,
                entity.getCostCenter() != null ? entity.getCostCenter().getCostCenterName() : null,
                entity.getFiscalYear(),
                entity.getBudgetAmount(),
                entity.getAvailableBudget(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
