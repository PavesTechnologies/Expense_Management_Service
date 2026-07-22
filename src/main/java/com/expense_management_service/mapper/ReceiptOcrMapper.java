package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.ReceiptOcrRequest;
import com.expense_management_service.dto.response.ReceiptOcrResponse;
import com.expense_management_service.entity.ReceiptOcr;
import org.springframework.stereotype.Component;

@Component
public class ReceiptOcrMapper {

    public ReceiptOcr toEntity(ReceiptOcrRequest request) {
        return ReceiptOcr.builder()
                .merchantName(request.merchantName())
                .receiptDate(request.receiptDate())
                .amount(request.amount())
                .currencyCode(request.currencyCode())
                .confidenceScore(request.confidenceScore())
                .build();
    }

    public void updateEntity(ReceiptOcr entity, ReceiptOcrRequest request) {
        entity.setMerchantName(request.merchantName());
        entity.setReceiptDate(request.receiptDate());
        entity.setAmount(request.amount());
        entity.setCurrencyCode(request.currencyCode());
        entity.setConfidenceScore(request.confidenceScore());
    }

    public ReceiptOcrResponse toResponse(ReceiptOcr entity) {
        return new ReceiptOcrResponse(
                entity.getOcrId(),
                entity.getReceipt() != null ? entity.getReceipt().getReceiptId() : null,
                entity.getMerchantName(),
                entity.getReceiptDate(),
                entity.getAmount(),
                entity.getCurrencyCode(),
                entity.getConfidenceScore(),
                entity.getProcessedAt()
        );
    }
}
