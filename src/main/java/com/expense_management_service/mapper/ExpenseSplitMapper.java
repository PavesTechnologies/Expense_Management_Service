package com.expense_management_service.mapper;

import com.expense_management_service.dto.response.ExpenseSplitResponse;
import com.expense_management_service.entity.ExpenseSplit;
import org.springframework.stereotype.Component;

@Component
public class ExpenseSplitMapper {

    public ExpenseSplitResponse toResponse(ExpenseSplit entity) {
        return new ExpenseSplitResponse(
                entity.getSplitId(),
                entity.getLineItem() != null ? entity.getLineItem().getLineItemId() : null,
                entity.getCostCenter() != null ? entity.getCostCenter().getCostCenterId() : null,
                entity.getCostCenter() != null ? entity.getCostCenter().getCostCenterName() : null,
                entity.getSplitType(),
                entity.getPercentage(),
                entity.getAllocatedAmount(),
                entity.getSplitOrder(),
                entity.getCreatedAt()
        );
    }
}
