package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.ApprovalMatrixRequest;
import com.expense_management_service.dto.response.ApprovalMatrixResponse;
import com.expense_management_service.entity.ApprovalMatrix;
import com.expense_management_service.enums.ApprovalMode;
import com.expense_management_service.enums.ApproverType;
import org.springframework.stereotype.Component;

@Component
public class ApprovalMatrixMapper {

    public ApprovalMatrix toEntity(ApprovalMatrixRequest request) {
        return ApprovalMatrix.builder()
                .minimumAmount(request.minimumAmount())
                .maximumAmount(request.maximumAmount())
                .approvalLevel(request.approvalLevel())
                .approverType(toApproverType(request.approverType()))
                .approverReference(request.approverReference())
                .status(request.status())
                .approvalMode(toApprovalMode(request.approvalMode()))
                .build();
    }

    public void updateEntity(ApprovalMatrix entity, ApprovalMatrixRequest request) {
        entity.setMinimumAmount(request.minimumAmount());
        entity.setMaximumAmount(request.maximumAmount());
        entity.setApprovalLevel(request.approvalLevel());
        entity.setApproverType(toApproverType(request.approverType()));
        entity.setApproverReference(request.approverReference());
        entity.setStatus(request.status());
        entity.setApprovalMode(toApprovalMode(request.approvalMode()));
    }

    public ApprovalMatrixResponse toResponse(ApprovalMatrix entity) {
        return new ApprovalMatrixResponse(
                entity.getMatrixId(),
                entity.getCostCenter() != null ? entity.getCostCenter().getCostCenterId() : null,
                entity.getCostCenter() != null ? entity.getCostCenter().getCostCenterName() : null,
                entity.getMinimumAmount(),
                entity.getMaximumAmount(),
                entity.getApprovalLevel(),
                entity.getApproverType() != null ? entity.getApproverType().name() : null,
                entity.getApproverReference(),
                entity.getStatus(),
                entity.getApprovalMode() != null ? entity.getApprovalMode().name() : null,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ApproverType toApproverType(String approverType) {
        return approverType != null ? ApproverType.valueOf(approverType) : null;
    }

    private ApprovalMode toApprovalMode(String approvalMode) {
        return approvalMode != null ? ApprovalMode.valueOf(approvalMode) : null;
    }
}
