package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.ApprovalDelegationRequest;
import com.expense_management_service.dto.response.ApprovalDelegationResponse;
import com.expense_management_service.entity.ApprovalDelegation;
import com.expense_management_service.enums.DelegationStatus;
import org.springframework.stereotype.Component;

@Component
public class ApprovalDelegationMapper {

    public ApprovalDelegation toEntity(ApprovalDelegationRequest request) {
        return ApprovalDelegation.builder()
                .delegatorId(request.delegatorId())
                .delegateId(request.delegateId())
                .startDate(request.startDate())
                .endDate(request.endDate())
                .status(toDelegationStatus(request.status()))
                .build();
    }

    public void updateEntity(ApprovalDelegation entity, ApprovalDelegationRequest request) {
        entity.setDelegatorId(request.delegatorId());
        entity.setDelegateId(request.delegateId());
        entity.setStartDate(request.startDate());
        entity.setEndDate(request.endDate());
        entity.setStatus(toDelegationStatus(request.status()));
    }

    public ApprovalDelegationResponse toResponse(ApprovalDelegation entity) {
        return new ApprovalDelegationResponse(
                entity.getDelegationId(),
                entity.getDelegatorId(),
                entity.getDelegateId(),
                entity.getStartDate(),
                entity.getEndDate(),
                entity.getStatus() != null ? entity.getStatus().name() : null,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private DelegationStatus toDelegationStatus(String status) {
        return status != null ? DelegationStatus.valueOf(status) : null;
    }
}
