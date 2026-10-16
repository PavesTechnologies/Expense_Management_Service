package com.expense_management_service.mapper;

import com.expense_management_service.dto.response.ReceiptOcrResponse;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ReceiptOcr;
import com.expense_management_service.service.ReceiptDuplicateService;
import org.springframework.stereotype.Component;

@Component
public class ReceiptOcrMapper {

    public ReceiptOcrResponse toResponse(ReceiptOcr entity) {
        return toResponse(entity, false, false, null);
    }

    /** Used by {@code OCRService}, which computes the duplicate/review flags and resolves the suggested category. */
    public ReceiptOcrResponse toResponse(ReceiptOcr entity, boolean possibleDuplicate, boolean reviewRecommended,
                                         ExpenseCategory suggestedCategory) {
        return toResponse(entity, possibleDuplicate, reviewRecommended, suggestedCategory, ReceiptDuplicateService.DuplicateSummary.NONE);
    }

    public ReceiptOcrResponse toResponse(ReceiptOcr entity, boolean possibleDuplicate, boolean reviewRecommended,
                                         ExpenseCategory suggestedCategory, ReceiptDuplicateService.DuplicateSummary duplicate) {
        return new ReceiptOcrResponse(
                entity.getOcrId(),
                entity.getReceipt() != null ? entity.getReceipt().getReceiptId() : null,
                entity.getMerchantName(),
                entity.getInvoiceNumber(),
                entity.getReceiptDate(),
                entity.getReceiptTime(),
                entity.getCurrencyCode(),
                entity.getSubtotal(),
                entity.getTaxAmount(),
                entity.getAmount(),
                entity.getPaymentMethod(),
                suggestedCategory != null ? suggestedCategory.getCategoryId() : null,
                suggestedCategory != null ? suggestedCategory.getCategoryName() : null,
                entity.getConfidenceScore(),
                entity.getProcessingStatus(),
                entity.getFailureReason(),
                entity.getProcessedAt(),
                entity.getProcessingDurationMs(),
                entity.getOcrEngine(),
                entity.getOcrVersion(),
                possibleDuplicate,
                reviewRecommended,
                duplicate.status(),
                duplicate.message()
        );
    }
}
