package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.CashAdvanceRepaymentRequest;
import com.expense_management_service.dto.response.CashAdvanceRepaymentResponse;
import com.expense_management_service.entity.CashAdvanceRepayment;
import org.springframework.stereotype.Component;

@Component
public class CashAdvanceRepaymentMapper {

    public CashAdvanceRepayment toEntity(CashAdvanceRepaymentRequest request) {
        return CashAdvanceRepayment.builder()
                .amount(request.amount())
                .paymentMethod(request.paymentMethod())
                .paymentReference(request.paymentReference())
                .notes(request.notes())
                .build();
    }

    public CashAdvanceRepaymentResponse toResponse(CashAdvanceRepayment entity) {
        return new CashAdvanceRepaymentResponse(
                entity.getRepaymentId(),
                entity.getCashAdvance() != null ? entity.getCashAdvance().getAdvanceId() : null,
                entity.getAmount(),
                entity.getPaymentMethod(),
                entity.getPaymentReference(),
                entity.getRepaidBy(),
                entity.getRepaidAt(),
                entity.getNotes()
        );
    }
}
