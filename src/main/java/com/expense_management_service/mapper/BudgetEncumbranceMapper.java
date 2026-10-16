package com.expense_management_service.mapper;

import com.expense_management_service.dto.response.BudgetEncumbranceResponse;
import com.expense_management_service.entity.BudgetEncumbrance;
import com.expense_management_service.entity.CostCenter;
import org.springframework.stereotype.Component;

@Component
public class BudgetEncumbranceMapper {

    public BudgetEncumbranceResponse toResponse(BudgetEncumbrance entity) {
        CostCenter costCenter = resolveCostCenter(entity);
        return new BudgetEncumbranceResponse(
                entity.getEncumbranceId(),
                entity.getReport() != null ? entity.getReport().getReportId() : null,
                entity.getSplit() != null ? entity.getSplit().getSplitId() : null,
                costCenter != null ? costCenter.getCostCenterId() : null,
                costCenter != null ? costCenter.getCostCenterName() : null,
                entity.getSubmissionCycle(),
                entity.getAmount(),
                entity.getStatus(),
                entity.getUnbudgeted(),
                entity.getCreatedAt(),
                entity.getReleasedAt(),
                entity.getConsumedAt()
        );
    }

    /**
     * Rows record their cost center directly; older rows don't, and fall back to the split's cost
     * center, the budget's, or (an unbudgeted remainder row) the report's own header Cost Center.
     */
    private CostCenter resolveCostCenter(BudgetEncumbrance entity) {
        if (entity.getCostCenter() != null) {
            return entity.getCostCenter();
        }
        if (entity.getSplit() != null) {
            return entity.getSplit().getCostCenter();
        }
        if (entity.getBudget() != null) {
            return entity.getBudget().getCostCenter();
        }
        return entity.getReport() != null ? entity.getReport().getCostCenter() : null;
    }
}
