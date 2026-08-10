package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.ReceiptOcrRequest;
import com.expense_management_service.dto.response.ReceiptOcrResponse;
import com.expense_management_service.entity.ReceiptOcr;
import org.springframework.stereotype.Component;

@Component
public class ReceiptOcrMapper {

    public ReceiptOcr toEntity(ReceiptOcrRequest request) {
        ReceiptOcr.ReceiptOcrBuilder builder = ReceiptOcr.builder()
                .merchantName(request.merchantName())
                .invoiceNumber(request.invoiceNumber())
                .receiptDate(request.receiptDate())
                .receiptTime(request.receiptTime())
                .currencyCode(request.currencyCode())
                .subtotal(request.subtotal())
                .taxAmount(request.taxAmount())
                .amount(request.totalAmount())
                .paymentMethod(request.paymentMethod())
                .confidenceScore(request.confidenceScore())
                .failureReason(request.failureReason());
        if (request.processingStatus() != null) {
            builder.processingStatus(request.processingStatus());
        }
        return builder.build();
    }

    public void updateEntity(ReceiptOcr entity, ReceiptOcrRequest request) {
        entity.setMerchantName(request.merchantName());
        entity.setInvoiceNumber(request.invoiceNumber());
        entity.setReceiptDate(request.receiptDate());
        entity.setReceiptTime(request.receiptTime());
        entity.setCurrencyCode(request.currencyCode());
        entity.setSubtotal(request.subtotal());
        entity.setTaxAmount(request.taxAmount());
        entity.setAmount(request.totalAmount());
        entity.setPaymentMethod(request.paymentMethod());
        entity.setConfidenceScore(request.confidenceScore());
        entity.setFailureReason(request.failureReason());
        if (request.processingStatus() != null) {
            entity.setProcessingStatus(request.processingStatus());
        }
    }

    /** Plain entity-to-response mapping, used by the generic admin CRUD controller — duplicate/review flags are not computed here since that's business logic, not mapping. */
    public ReceiptOcrResponse toResponse(ReceiptOcr entity) {
        return toResponse(entity, false, false);
    }

    /** Used by {@code OCRService}, which computes the duplicate/review flags and passes them in. */
    public ReceiptOcrResponse toResponse(ReceiptOcr entity, boolean possibleDuplicate, boolean reviewRecommended) {
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
                entity.getConfidenceScore(),
                entity.getProcessingStatus(),
                entity.getFailureReason(),
                entity.getProcessedAt(),
                entity.getProcessingDurationMs(),
                entity.getOcrEngine(),
                entity.getOcrVersion(),
                possibleDuplicate,
                reviewRecommended
        );
    }
}
