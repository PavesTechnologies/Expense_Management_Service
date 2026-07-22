package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.CostAllocationRequest;
import com.expense_management_service.dto.response.CostAllocationResponse;
import com.expense_management_service.entity.CostAllocation;
import org.springframework.stereotype.Component;

@Component
public class CostAllocationMapper {

    public CostAllocation toEntity(CostAllocationRequest request) {
        return CostAllocation.builder()
                .allocationPercentage(request.allocationPercentage())
                .allocationAmount(request.allocationAmount())
                .build();
    }

    public void updateEntity(CostAllocation entity, CostAllocationRequest request) {
        entity.setAllocationPercentage(request.allocationPercentage());
        entity.setAllocationAmount(request.allocationAmount());
    }

    public CostAllocationResponse toResponse(CostAllocation entity) {
        return new CostAllocationResponse(
                entity.getAllocationId(),
                entity.getLineItem() != null ? entity.getLineItem().getLineItemId() : null,
                entity.getCostCenter() != null ? entity.getCostCenter().getCostCenterId() : null,
                entity.getCostCenter() != null ? entity.getCostCenter().getCostCenterName() : null,
                entity.getAllocationPercentage(),
                entity.getAllocationAmount(),
                entity.getCreatedAt()
        );
    }
}
