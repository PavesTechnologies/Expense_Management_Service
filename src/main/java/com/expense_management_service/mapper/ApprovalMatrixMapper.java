package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.ApprovalMatrixRequest;
import com.expense_management_service.dto.response.ApprovalMatrixResponse;
import com.expense_management_service.entity.ApprovalMatrix;
import org.springframework.stereotype.Component;

@Component
public class ApprovalMatrixMapper {

    public ApprovalMatrix toEntity(ApprovalMatrixRequest request) {
        return ApprovalMatrix.builder()
                .minimumAmount(request.minimumAmount())
                .maximumAmount(request.maximumAmount())
                .approvalLevel(request.approvalLevel())
                .approverType(request.approverType())
                .approverReference(request.approverReference())
                .status(request.status())
                .build();
    }

    public void updateEntity(ApprovalMatrix entity, ApprovalMatrixRequest request) {
        entity.setMinimumAmount(request.minimumAmount());
        entity.setMaximumAmount(request.maximumAmount());
        entity.setApprovalLevel(request.approvalLevel());
        entity.setApproverType(request.approverType());
        entity.setApproverReference(request.approverReference());
        entity.setStatus(request.status());
    }

    public ApprovalMatrixResponse toResponse(ApprovalMatrix entity) {
        return new ApprovalMatrixResponse(
                entity.getMatrixId(),
                entity.getCostCenter() != null ? entity.getCostCenter().getCostCenterId() : null,
                entity.getCostCenter() != null ? entity.getCostCenter().getCostCenterName() : null,
                entity.getMinimumAmount(),
                entity.getMaximumAmount(),
                entity.getApprovalLevel(),
                entity.getApproverType(),
                entity.getApproverReference(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
