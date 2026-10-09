package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.CashAdvanceRequest;
import com.expense_management_service.dto.response.CashAdvanceResponse;
import com.expense_management_service.entity.CashAdvance;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class CashAdvanceMapper {

    public CashAdvance toEntity(CashAdvanceRequest request) {
        String purpose = request.purpose();
        if ((purpose == null || purpose.isBlank()) && request.title() != null && !request.title().isBlank()) {
            purpose = request.title();
            if (request.notes() != null && !request.notes().isBlank()) {
                purpose += " - " + request.notes();
            }
        } else if ((purpose == null || purpose.isBlank()) && request.notes() != null && !request.notes().isBlank()) {
            purpose = request.notes();
        }

        LocalDate dueDate = request.settlementDueDate() != null ? request.settlementDueDate() : request.neededByDate();

        return CashAdvance.builder()
                .amount(request.amount())
                .baseAmount(request.baseAmount())
                .purpose(purpose)
                .settlementDueDate(dueDate)
                .build();
    }

    public void updateEntity(CashAdvance entity, CashAdvanceRequest request) {
        entity.setAmount(request.amount());
        entity.setBaseAmount(request.baseAmount());

        String purpose = request.purpose();
        if ((purpose == null || purpose.isBlank()) && request.title() != null && !request.title().isBlank()) {
            purpose = request.title();
            if (request.notes() != null && !request.notes().isBlank()) {
                purpose += " - " + request.notes();
            }
        } else if ((purpose == null || purpose.isBlank()) && request.notes() != null && !request.notes().isBlank()) {
            purpose = request.notes();
        }
        if (purpose != null && !purpose.isBlank()) {
            entity.setPurpose(purpose);
        }

        LocalDate dueDate = request.settlementDueDate() != null ? request.settlementDueDate() : request.neededByDate();
        if (dueDate != null) {
            entity.setSettlementDueDate(dueDate);
        }
    }

    public CashAdvanceResponse toResponse(CashAdvance entity) {
        return new CashAdvanceResponse(
                entity.getAdvanceId(),
                entity.getEmployeeId(),
                entity.getManagerId(),
                entity.getCostCenter() != null ? entity.getCostCenter().getCostCenterId() : null,
                entity.getCostCenter() != null ? entity.getCostCenter().getCostCenterName() : null,
                entity.getAmount(),
                entity.getCurrency() != null ? entity.getCurrency().getCurrencyId() : null,
                entity.getCurrency() != null ? entity.getCurrency().getCurrencyCode() : null,
                entity.getBaseAmount(),
                entity.getPurpose(),
                entity.getStatus(),
                entity.getSettlementDueDate(),
                entity.getSettlementDueDate(),
                entity.getOutstandingBalance(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}

