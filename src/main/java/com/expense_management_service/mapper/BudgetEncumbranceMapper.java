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
     * A split-derived row carries its own Cost Center via the split. A report-level (remainder or
     * fully-unsplit) row does not - {@code budget} can be null too when {@code unbudgeted == true},
     * so the only reference that is ALWAYS present is the report's own header Cost Center.
     */
    private CostCenter resolveCostCenter(BudgetEncumbrance entity) {
        if (entity.getSplit() != null) {
            return entity.getSplit().getCostCenter();
        }
        if (entity.getBudget() != null) {
            return entity.getBudget().getCostCenter();
        }
        return entity.getReport() != null ? entity.getReport().getCostCenter() : null;
    }
}
