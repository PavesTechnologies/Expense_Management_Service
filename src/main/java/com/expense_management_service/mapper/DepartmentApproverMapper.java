package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.DepartmentApproverRequest;
import com.expense_management_service.dto.response.DepartmentApproverResponse;
import com.expense_management_service.entity.DepartmentApprover;
import org.springframework.stereotype.Component;

@Component
public class DepartmentApproverMapper {

    public DepartmentApprover toEntity(DepartmentApproverRequest request) {
        return DepartmentApprover.builder()
                .departmentUuid(request.departmentUuid())
                .approverEmployeeId(request.approverEmployeeId())
                .status(request.status())
                .build();
    }

    public void updateEntity(DepartmentApprover entity, DepartmentApproverRequest request) {
        entity.setDepartmentUuid(request.departmentUuid());
        entity.setApproverEmployeeId(request.approverEmployeeId());
        entity.setStatus(request.status());
    }

    public DepartmentApproverResponse toResponse(DepartmentApprover entity) {
        return new DepartmentApproverResponse(
                entity.getDepartmentApproverId(),
                entity.getDepartmentUuid(),
                entity.getApproverEmployeeId(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
