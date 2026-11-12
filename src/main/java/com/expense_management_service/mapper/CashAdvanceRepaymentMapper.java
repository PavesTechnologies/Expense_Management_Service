package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.CashAdvanceDisbursement;
import com.expense_management_service.dto.request.CashAdvanceRepaymentRequest;
import com.expense_management_service.dto.response.CashAdvanceRepaymentResponse;
import com.expense_management_service.entity.CashAdvanceRepayment;
import org.springframework.stereotype.Component;

@Component
public class CashAdvanceRepaymentMapper {

    /**
     * Builds the entity from a validated request. The caller resolves the reference and proof key, because they
     * depend on the uploaded files and the duplicate check. Only the fields for the chosen mode are written.
     */
    public CashAdvanceRepayment toEntity(CashAdvanceRepaymentRequest request, String mode, String reference, String proofKey) {
        CashAdvanceDisbursement payment = request.payment();
        CashAdvanceRepayment.CashAdvanceRepaymentBuilder builder = CashAdvanceRepayment.builder()
                .amount(request.amount())
                .paymentMethod(mode)
                .paymentReference(reference)
                .paymentMode(mode)
                .notes(request.notes() == null || request.notes().isBlank() ? null : request.notes().trim())
                .confirmationNote(blankToNull(payment.confirmationNote()))
                .proofReference(proofKey);

        switch (mode) {
            case "CASH" -> builder.receivedByName(payment.receivedByName().trim());
            case "BANK_TRANSFER" -> builder
                    .transferDate(payment.transferDate())
                    .transferDetails(payment.transferDetails().trim());
            case "UPI" -> builder
                    .transferDate(payment.transferDate())
                    .payeeUpiId(payment.payeeUpiId().trim());
            default -> throw new IllegalStateException("Unhandled repayment mode: " + mode);
        }
        return builder.build();
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
                entity.getNotes(),
                entity.getTransferDate(),
                entity.getPaymentMode(),
                entity.getProofReference() != null
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
