package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.CashAdvanceAdjustmentRequest;
import com.expense_management_service.dto.response.CashAdvanceAdjustmentResponse;
import com.expense_management_service.entity.CashAdvanceAdjustment;
import org.springframework.stereotype.Component;

@Component
public class CashAdvanceAdjustmentMapper {

    public CashAdvanceAdjustment toEntity(CashAdvanceAdjustmentRequest request) {
        return CashAdvanceAdjustment.builder()
                .adjustedAmount(request.adjustedAmount())
                .adjustedBy(request.adjustedBy())
                .build();
    }

    public void updateEntity(CashAdvanceAdjustment entity, CashAdvanceAdjustmentRequest request) {
        entity.setAdjustedAmount(request.adjustedAmount());
        entity.setAdjustedBy(request.adjustedBy());
    }

    public CashAdvanceAdjustmentResponse toResponse(CashAdvanceAdjustment entity) {
        return new CashAdvanceAdjustmentResponse(
                entity.getAdjustmentId(),
                entity.getCashAdvance() != null ? entity.getCashAdvance().getAdvanceId() : null,
                entity.getReport() != null ? entity.getReport().getReportId() : null,
                entity.getAdjustedAmount(),
                entity.getAdjustedBy(),
                entity.getAdjustedAt()
        );
    }
}
