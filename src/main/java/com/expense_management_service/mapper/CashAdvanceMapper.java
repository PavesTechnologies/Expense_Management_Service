package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.CashAdvanceRequest;
import com.expense_management_service.dto.response.CashAdvanceResponse;
import com.expense_management_service.entity.CashAdvance;
import org.springframework.stereotype.Component;

@Component
public class CashAdvanceMapper {

    public CashAdvance toEntity(CashAdvanceRequest request) {
        return CashAdvance.builder()
                .employeeId(request.employeeId())
                .amount(request.amount())
                .baseAmount(request.baseAmount())
                .purpose(request.purpose())
                .status(request.status())
                .settlementDueDate(request.settlementDueDate())
                .outstandingBalance(request.outstandingBalance())
                .build();
    }

    public void updateEntity(CashAdvance entity, CashAdvanceRequest request) {
        entity.setEmployeeId(request.employeeId());
        entity.setAmount(request.amount());
        entity.setBaseAmount(request.baseAmount());
        entity.setPurpose(request.purpose());
        entity.setStatus(request.status());
        entity.setSettlementDueDate(request.settlementDueDate());
        entity.setOutstandingBalance(request.outstandingBalance());
    }

    public CashAdvanceResponse toResponse(CashAdvance entity) {
        return new CashAdvanceResponse(
                entity.getAdvanceId(),
                entity.getEmployeeId(),
                entity.getAmount(),
                entity.getCurrency() != null ? entity.getCurrency().getCurrencyId() : null,
                entity.getCurrency() != null ? entity.getCurrency().getCurrencyCode() : null,
                entity.getBaseAmount(),
                entity.getPurpose(),
                entity.getStatus(),
                entity.getSettlementDueDate(),
                entity.getOutstandingBalance()
        );
    }
}
