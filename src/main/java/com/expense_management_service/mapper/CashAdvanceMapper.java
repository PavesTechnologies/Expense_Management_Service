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
                .employeeId(request.employeeId())
                .amount(request.amount())
                .baseAmount(request.baseAmount())
                .purpose(purpose)
                .status(request.status())
                .settlementDueDate(dueDate)
                .outstandingBalance(request.outstandingBalance())
                .build();
    }

    public void updateEntity(CashAdvance entity, CashAdvanceRequest request) {
        if (request.employeeId() != null && !request.employeeId().isBlank()) {
            entity.setEmployeeId(request.employeeId());
        }
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

        if (request.status() != null && !request.status().isBlank()) {
            entity.setStatus(request.status());
        }

        LocalDate dueDate = request.settlementDueDate() != null ? request.settlementDueDate() : request.neededByDate();
        if (dueDate != null) {
            entity.setSettlementDueDate(dueDate);
        }
        if (request.outstandingBalance() != null) {
            entity.setOutstandingBalance(request.outstandingBalance());
        }
    }

    public CashAdvanceResponse toResponse(CashAdvance entity) {
        return new CashAdvanceResponse(
                entity.getAdvanceId(),
                entity.getEmployeeId(),
                entity.getManagerId(),
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

