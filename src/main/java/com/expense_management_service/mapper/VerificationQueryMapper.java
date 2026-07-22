package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.VerificationQueryRequest;
import com.expense_management_service.dto.response.VerificationQueryResponse;
import com.expense_management_service.entity.VerificationQuery;
import org.springframework.stereotype.Component;

@Component
public class VerificationQueryMapper {

    public VerificationQuery toEntity(VerificationQueryRequest request) {
        return VerificationQuery.builder()
                .raisedBy(request.raisedBy())
                .queryText(request.queryText())
                .employeeResponse(request.employeeResponse())
                .status(request.status())
                .build();
    }

    public void updateEntity(VerificationQuery entity, VerificationQueryRequest request) {
        entity.setRaisedBy(request.raisedBy());
        entity.setQueryText(request.queryText());
        entity.setEmployeeResponse(request.employeeResponse());
        entity.setStatus(request.status());
    }

    public VerificationQueryResponse toResponse(VerificationQuery entity) {
        return new VerificationQueryResponse(
                entity.getQueryId(),
                entity.getLineItem() != null ? entity.getLineItem().getLineItemId() : null,
                entity.getRaisedBy(),
                entity.getQueryText(),
                entity.getEmployeeResponse(),
                entity.getStatus(),
                entity.getRaisedAt(),
                entity.getResolvedAt()
        );
    }
}
