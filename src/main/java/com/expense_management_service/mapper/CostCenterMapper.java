package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.CostCenterRequest;
import com.expense_management_service.dto.response.CostCenterResponse;
import com.expense_management_service.entity.CostCenter;
import org.springframework.stereotype.Component;

@Component
public class CostCenterMapper {

    public CostCenter toEntity(CostCenterRequest request) {
        return CostCenter.builder()
                .costCenterCode(request.costCenterCode())
                .costCenterName(request.costCenterName())
                .departmentUuid(request.departmentUuid())
                .description(request.description())
                .ownerEmployeeId(request.ownerEmployeeId())
                .status(request.status())
                .build();
    }

    public void updateEntity(CostCenter entity, CostCenterRequest request) {
        entity.setCostCenterCode(request.costCenterCode());
        entity.setCostCenterName(request.costCenterName());
        entity.setDepartmentUuid(request.departmentUuid());
        entity.setDescription(request.description());
        entity.setOwnerEmployeeId(request.ownerEmployeeId());
        entity.setStatus(request.status());
    }

    public CostCenterResponse toResponse(CostCenter entity) {
        return new CostCenterResponse(
                entity.getCostCenterId(),
                entity.getCostCenterCode(),
                entity.getCostCenterName(),
                entity.getDepartmentUuid(),
                entity.getDescription(),
                entity.getOwnerEmployeeId(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
